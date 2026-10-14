package org.apache.cordova;
public class CallbackContext { public String last; public void success(String s){ last = s; } public void error(String s){ last = "ERR:" + s; } }
