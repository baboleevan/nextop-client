package io.nextop.cordova;

import io.nextop.Id;

import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaArgs;
import org.apache.cordova.CallbackContext;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

import java.util.Map;
import java.util.HashMap;

import java.lang.Override;

// FIXME set e X-Requested-With header to XMLHttpRequest
// see http://stackoverflow.com/questions/15945118/detecting-ajax-requests-on-nodejs-with-express
public class Nextop extends CordovaPlugin implements io.nextop.NextopContext {

    private io.nextop.Nextop nextop;

    // JS id to Nextop Id translation
    Map<Integer, Id> idTranslationMap = new HashMap<Integer, Id>(32);


    public Nextop() {
    }


    @Override
    public io.nextop.Nextop getNextop() {
        return nextop;
    }


    @Override
    protected void pluginInitialize() {
        String accessKey = preferences.getString("NextopAccessKey", null);
        // TODO
        String[] grantKeys = new String[0];

        nextop = io.nextop.Nextop.create(cordova.getActivity(), accessKey, grantKeys).start();
    }

    @Override
    public void onDestroy() {
        nextop = nextop.stop();
    }

    @Override
    public boolean execute(String action, CordovaArgs args, final CallbackContext callbackContext) throws JSONException {

        if ("send".equals(action)) {
            // FIXME
            // FIXME add to translation map, and remove on complete or error

            return true;
        } else if ("abort".equals(action)) {
            // FIXME

            return true;
        } else {
            return false;
        }
    }
}
