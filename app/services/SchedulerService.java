package services;

import java.io.*;
import java.sql.*;
import java.util.*;
import java.net.URL;
import java.security.MessageDigest;

import javax.inject.Singleton;
import javax.inject.Inject;

import org.h2.tools.RunScript;
import com.avaje.ebean.Ebean;

import play.Environment;
import play.Configuration;
import play.Logger;
import play.inject.Injector;
import play.inject.ApplicationLifecycle;
import play.db.NamedDatabase;
import play.db.Database;

import play.db.*;
import play.libs.F;

import static akka.pattern.Patterns.ask;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;

import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;
import org.quartz.impl.matchers.GroupMatcher;
import org.quartz.utils.ConnectionProvider;
import org.quartz.listeners.TriggerListenerSupport;
import org.quartz.listeners.JobListenerSupport;
import org.quartz.Trigger.CompletedExecutionInstruction;

import static org.quartz.JobBuilder.*; 
import static org.quartz.SimpleScheduleBuilder.*; 
import static org.quartz.CronScheduleBuilder.*; 
import static org.quartz.CalendarIntervalScheduleBuilder.*; 
import static org.quartz.TriggerBuilder.*; 
import static org.quartz.DateBuilder.*;

import org.reflections.Reflections;
import services.jobs.*;
import models.Payload;

import ix.curation.DataSource;

@Singleton
public class SchedulerService {
    static final String QUARTZ = "quartz";
    static final String QUARTZ_INIT = "ix.quartz.init";
    static final String QUARTZ_THREADS = "ix.quartz.threads";

    // this is not pretty..we have to do this because injection doesn't
    // work with quartz through its connectionProvider.class property
    static private Database _quartzDb;
    
    static public class QuartzConnectionProvider
        implements ConnectionProvider {
        public QuartzConnectionProvider () {}

        public Connection getConnection () throws SQLException {
            return _quartzDb.getConnection();        
        }
        public void initialize () throws SQLException {}
        public void shutdown () throws SQLException {}
    }

    class SchedulerJobListener extends JobListenerSupport {
        SchedulerJobListener () {
        }
        
        @Override
        public String getName () { return getClass().getName(); }
    }

    class SchedulerTriggerListener extends TriggerListenerSupport {
        SchedulerTriggerListener () {}
        
        @Override
        public String getName () { return getClass().getName(); }
        
        @Override
        public void triggerComplete (Trigger trigger, JobExecutionContext ctx,
                                     CompletedExecutionInstruction execInst) {
            
            final String key = trigger.getKey().getName();
            Object result = ctx.getResult();
            
            Ebean.execute(() -> {
                    List<models.Job> jobs = models.Job.find
                        .where().eq("key", key).findList();

                    if (jobs.isEmpty()) {
                        Logger.error("Can't retrieve job "+key);
                    }
                    else {
                        models.Job job = jobs.iterator().next();
                        switch (execInst) {
                        case SET_TRIGGER_ERROR:
                        case SET_ALL_JOB_TRIGGERS_ERROR:
                            job.setStatus(models.Job.Status.FAILED);
                            break;
                        default:
                            job.setStatus(models.Job.Status.DONE);
                        }
                        
                        job.setFinished(System.currentTimeMillis());
                        job.save();
                    }
                });

            Logger.debug("Trigger "+key +" complete; result="+ctx.getResult());
            cache.set(key, ctx.getResult());
        }

        @Override
        public void triggerFired (Trigger trigger, JobExecutionContext ctx) {
            Ebean.execute(() -> {
                    models.Job job = new models.Job();
                    job.key = trigger.getKey().getName();
                    job.status = models.Job.Status.FIRED;
                    job.name = ctx.getJobInstance().getClass().getName();
                    job.started = ctx.getFireTime().getTime();
                    if (ctx.getMergedJobDataMap()
                        .containsKey(JobParams.PAYLOAD)) {
                        Payload payload = Payload.find
                            .byId(ctx.getMergedJobDataMap()
                                  .getLong(JobParams.PAYLOAD));
                        job.setPayload(payload);
                    }
                    job.save();
                    
                    Logger.debug("++ Job "+job.id+" created.. "+job.key);
                });
        }
    }

    Scheduler scheduler;
    MessageDigest digest;
    @Inject CacheService cache;

    @Inject
    public SchedulerService (Environment env, Configuration config,
                             Injector injector,
                             @NamedDatabase("quartz") Database quartzDb,
                             ApplicationLifecycle lifecycle) throws Exception {
        digest = MessageDigest.getInstance("SHA1");
        
        // not cool.. but ok since this is a singleton class
        initDb (env, config, quartzDb);
        SchedulerService._quartzDb = quartzDb;
        
        scheduler = createScheduler (config, injector);
        scheduler.getListenerManager().addJobListener
            (new SchedulerJobListener ());
        scheduler.getListenerManager().addTriggerListener
            (new SchedulerTriggerListener ());
        scheduler.start(); // now start the scheduler
        
        // setup startup jobs
        startupJobs ();
        
        Logger.debug(getJobCount()+" job(s) stored in queue!");
        for (JobKey job :
                 scheduler.getJobKeys(GroupMatcher.anyJobGroup())) {
            Logger.debug("++ "+job.getName());
        }
        
        lifecycle.addStopHook(() -> {
                shutdown ();
                return F.Promise.pure(null);
            });
    }

    void startupJobs () throws Exception {
        Reflections reflections = new Reflections ("services.jobs");
        for (Class c : reflections.getTypesAnnotatedWith(StartUpJob.class)) {
            StartUpJob startup = (StartUpJob)c.getAnnotation(StartUpJob.class);
            if (Job.class.isAssignableFrom(c)) {
                Logger.debug("Starting job "+c.getName()
                             +" with priority "+startup.priority()+"...");
                
                JobKey job = getJob (c);
                Trigger trigger = TriggerBuilder
                    .newTrigger()
                    .startNow()
                    .forJob(job)
                    .withPriority(startup.priority())
                    .build();
                // start this job..
                scheduler.scheduleJob(trigger);
            }
            else {
                Logger.warn("Can't use "+StartUpJob.class.getName()
                            +" annotation for class "+c.getName()+" because "
                            +" it's not an instance of "+Job.class.getName());
            }
        }
    }
    
    protected synchronized JobKey getJob
        (Class<? extends Job> jobClass) throws SchedulerException {
        return getJob (jobClass, null);
    }

    protected synchronized JobKey getJob
        (Class<? extends Job> jobClass, Set<String> params)
        throws SchedulerException {
        
        // calculate a unique job key based on the parameters keys
        JobKey jobid;
        if (params != null && !params.isEmpty()) {
            digest.reset();
            for (String k : params) {
                digest.update(k.getBytes());
            }
            
            byte[] sig = digest.digest();
            StringBuilder sha = new StringBuilder ();
            for (int i = 0; i < sig.length; ++i)
                sha.append(String.format("%1$02x", sig[i] & 0xff));
            jobid = new JobKey (jobClass.getName()+"/"+sha);
        }
        else {
            jobid = new JobKey (jobClass.getName());
        }
        
        JobDetail job = scheduler.getJobDetail(jobid);
        if (job == null) {
            job = JobBuilder
                .newJob(jobClass)
                .storeDurably(true)
                .requestRecovery(true)
                .withIdentity(jobid)
                .build();
            scheduler.addJob(job, true);
        }
        return jobid;
    }

    protected Trigger setupJob (Class<? extends Job> jobClass,
                                Map<String, Object> params)
        throws SchedulerException {
        Map<String, Object> sorted = new TreeMap<String, Object>();
        if (params != null) {
            for (Map.Entry<String, Object> me : params.entrySet()) {
                Object value = me.getValue();
                if (value instanceof Serializable) {
                    sorted.put(me.getKey(), value);
                }
                else {
                    Logger.warn("Job "+jobClass+"; ignoring parameter \""
                                +me.getKey()
                                +"\" because value is not serializable!");
                }
            }
        }
        
        JobKey job = getJob (jobClass, sorted.keySet());
        Trigger trigger = TriggerBuilder
            .newTrigger()
            .startNow()
            .forJob(job)
            .build();
        trigger.getJobDataMap().putAll(sorted);

        return trigger;
    }

    public String submit (Class<? extends RegistrationJob> jobClass,
                          Map<String, Object> params,
                          File file) throws SchedulerException {
        Trigger trigger = setupJob (jobClass, params);
        trigger.getJobDataMap().put(RegistrationJob.FILE, file);        
        scheduler.scheduleJob(trigger);
        
        return trigger.getKey().getName();
    }

    public String submit (Class<? extends RegistrationJob> jobClass,
                          Map<String, Object> params,
                          URL url) throws SchedulerException {
        Trigger trigger = setupJob (jobClass, params);
        trigger.getJobDataMap().put(RegistrationJob.URL, url);
        scheduler.scheduleJob(trigger);
        
        return trigger.getKey().getName();
    }

    public String submit (Class<? extends Job> jobClass,
                          Map<String, Object> params,
                          models.Payload payload) throws SchedulerException {
        Trigger trigger = setupJob (jobClass, params);
        trigger.getJobDataMap().put(JobParams.PAYLOAD, payload.id);
        scheduler.scheduleJob(trigger);
        return trigger.getKey().getName();
    }

    public String submit (Class<? extends Job> jobClass,
                          Map<String, Object> params)
        throws SchedulerException {
        Trigger trigger = setupJob (jobClass, params);
        scheduler.scheduleJob(trigger);
        return trigger.getKey().getName();
    }

    Set<JobKey> getJobs () {
        Set<JobKey> jobs = new TreeSet<JobKey>();
        try {
            for (JobKey jk :
                     scheduler.getJobKeys(GroupMatcher.anyJobGroup())) {
                jobs.add(jk);
            }
        }
        catch (Exception ex) {
            ex.printStackTrace();
        }
        
        return jobs;
    }

    int getJobCount () { return getJobs().size(); }
    
    public List<Map> getRunningJobs () throws SchedulerException {
        List<Map> jobs = new ArrayList<Map>();
        for (JobExecutionContext ctx : scheduler.getCurrentlyExecutingJobs()) {
            String id = ctx.getTrigger().getKey().getName();
            Map map = new HashMap ();
            map.put("id", id);
            map.put("start", ctx.getFireTime());
            //map.put("elapsed", ctx.getJobRunTime());
            //map.put("result", ctx.getResult());
            map.putAll(ctx.getMergedJobDataMap());
            map.put("kind", ctx.getJobDetail().getJobClass().getName());
            Job job = ctx.getJobInstance();
            if (job instanceof RegistrationJob) {
                map.put("count", ((RegistrationJob)job).getCount());
                map.put("error", ((RegistrationJob)job).getError());
            }
            jobs.add(map);
        }
        return jobs;
    }

    public void shutdown () {
        if (scheduler != null) {
            Logger.debug("Shutting down "+getClass()+"..."+scheduler);
            try {
                scheduler.shutdown(true);
            }
            catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    static void initDb (Environment env, Configuration config, Database db)
        throws Exception {
        try (Connection con = db.getConnection()) {
            Statement stmt = con.createStatement();
            ResultSet rset = stmt.executeQuery
                ("select count(*) from information_schema.tables "
                 +"where table_name like 'QRTZ_%'");
            
            int tables = 0;
            if (rset.next()) {
                tables = rset.getInt(1);
            }
            rset.close();
            stmt.close();

            if (tables == 0) {
                String quartz = config.getString(QUARTZ_INIT);
                if (env.isProd()) {
                    RunScript.execute(con, new InputStreamReader
                                      (env.resourceAsStream(quartz)));
                }
                else {
                    File file = env.getFile("conf/"+quartz);
                    Logger.debug("Running script "+file+"...");
                    RunScript.execute(con, new FileReader (file));
                }
            }
            Logger.debug(SchedulerService.class+ "... initialized!");
        }
    }

    static Properties createSchedulerConfig (Configuration config) {
        Properties props = new Properties ();
        props.put("org.quartz.threadPool.threadCount",
                  config.getString(QUARTZ_THREADS, "4"));
        props.put("org.quartz.jobStore.class",
                  "org.quartz.impl.jdbcjobstore.JobStoreTX");
        props.put("org.quartz.jobStore.driverDelegateClass",
                  "org.quartz.impl.jdbcjobstore.StdJDBCDelegate");
        props.put("org.quartz.dataSource.quartzDS.connectionProvider.class",
                  QuartzConnectionProvider.class.getName());
        props.put("org.quartz.jobStore.dataSource", "quartzDS");
        return props;
    }

    static Scheduler createScheduler (Configuration config, Injector injector)
        throws Exception {
        SchedulerFactory schedulerFactory =
            new StdSchedulerFactory (createSchedulerConfig (config));
        Scheduler scheduler = schedulerFactory.getScheduler();
        scheduler.setJobFactory(new InjectorJobFactory (injector));
        
        Logger.debug("Scheduler initialized..."+scheduler);
        return scheduler;
    }
}
