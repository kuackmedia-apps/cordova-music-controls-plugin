package com.homerours.musiccontrols;
import android.app.Notification;
/** Doble de prueba del servicio real: registra llamadas y devuelve el resultado programado. */
public class MusicControlsNotificationKiller {
    static final int FGS_OK = 0, FGS_DENIED = 1, FGS_IGNORED = 2;
    /** Orden de las llamadas (start, detach, remove), compartido con los cancel del harness. */
    static final StringBuilder trace = new StringBuilder();
    int nextResult = FGS_OK;
    /** IGNORED con las dos señales en desacuerdo: el real no hace el stop defensivo y queda mayBeForeground. */
    boolean nextUncertain = false;
    int starts = 0, stopsDetach = 0, stopsRemove = 0;
    boolean foreground = false, mayBe = false;
    String lastError = "";
    int setForeground(Notification n) {
        starts++;
        trace.append("start ");
        if (nextResult == FGS_OK) { foreground = true; mayBe = true; lastError = ""; }
        else if (nextResult == FGS_DENIED) lastError = "not-allowed: test";
        else { lastError = "running-service foreground=false type=" + (nextUncertain ? "2" : "0"); mayBe = nextUncertain; }
        return nextResult;
    }
    void clearForeground(boolean remove) {
        if (remove) stopsRemove++; else stopsDetach++;
        trace.append(remove ? "remove " : "detach ");
        foreground = false; mayBe = false;
    }
    boolean mayBeForeground() { return mayBe; }
    String getLastError() { return lastError; }
}
