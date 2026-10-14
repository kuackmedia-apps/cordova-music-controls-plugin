package org.json;
import java.util.*;
public class JSONObject {
    private final LinkedHashMap<String,Object> m = new LinkedHashMap<>();
    public JSONObject put(String k, Object v) throws JSONException { m.put(k, v); return this; }
    public JSONObject put(String k, long v) throws JSONException { m.put(k, v); return this; }
    public JSONObject put(String k, int v) throws JSONException { m.put(k, v); return this; }
    public String toString() { StringBuilder b = new StringBuilder("{"); for (Map.Entry<String,Object> e : m.entrySet()) { if (b.length() > 1) b.append(','); b.append('"').append(e.getKey()).append("\":"); Object v = e.getValue(); if (v instanceof String) b.append('"').append(v).append('"'); else b.append(v); } return b.append('}').toString(); }
}
