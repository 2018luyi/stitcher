package controllers.app;

import java.util.*;
import java.io.*;
import javax.inject.*;
import java.net.URI;

import play.*;
import play.mvc.*;
import play.libs.ws.*;
import static play.mvc.Http.MultipartFormData.*;
import play.db.ebean.Transactional;

import views.html.*;

import services.GraphDbService;
import services.SchedulerService;
import services.CoreService;
import services.jobs.*;

import ix.curation.*;
import models.*;

public class App extends Controller {
    //@Inject WSClient ws;
    @Inject public SchedulerService scheduler;
    @Inject play.Application app;
    @Inject public GraphDbService graphDb;
    @Inject public CoreService service;

    public App () {
    }
    
    public Result build () {
        return ok(welcome.render("Build: "+ix.BuildInfo.TIME+" ("
                                 +ix.BuildInfo.BRANCH+"-"
                                 +ix.BuildInfo.COMMIT+")"));
    }

    public Result console (String key) {
        return ok (console.render(this, key));
    }

    public Result uploadForm () {
        return ok (upload.render(this));
    }

    @Transactional
    @BodyParser.Of(value = BodyParser.MultipartFormData.class, 
                   maxLength = 1024*1024*1000000)
    public Result upload () {
        if (request().body().isMaxSizeExceeded()) {
            return badRequest (error.render("File too large!"));
        }

        Http.MultipartFormData body = request().body().asMultipartFormData();
        Map<String, String[]> params = body.asFormUrlEncoded();
        FilePart part = body.getFile("file");
        //Logger.debug("part="+part+" uri="+params.get("uri"));
        if (part != null) {
            try {
                models.Payload payload = service.upload(part, params);
            }
            catch (Exception ex) {
                ex.printStackTrace();
                flash ("error", ex.getMessage());
                return redirect (routes.App.uploadForm());
            }
        }
        else {
            String[] uri = params.get("uri");
            if (uri == null || uri.length == 0 || uri[0].equals("")) {
                flash ("error", "Either File and/or URI must be specified!");
                return redirect (routes.App.uploadForm());
            }
            try {
                models.Payload payload = service.upload
                    (new URI (uri[0]), params);
            }
            catch (Exception ex) {
                ex.printStackTrace();
                
                flash ("error", ex.getMessage());
                return redirect (routes.App.uploadForm());              
            }
        }
        
        return redirect (routes.App.payload());
    }

    public Result payload () {
        List<models.Payload> payloads = service.getPayloads();
        return ok (payload.render(this, payloads));
    }

    public Result getPayload (String key) {
        models.Payload payload = service.getPayload(key);
        return ok (payload != null ? payloaddetails.render(this, payload)
                   : error.render("Invalid payload: "+key));
    }

    public Result setup () {
        return ok (payloadsetup.render());
    }

    public Result dashboard () {
        return ok (dashboard.render(this));
    }

    public Result delete (String key) {
        models.Payload payload = service.deletePayload(key);
        if (payload != null) {
            flash ("message", "Successfully deleted payload "+payload.sha1());
        }
        return redirect (routes.App.payload());
    }
}
