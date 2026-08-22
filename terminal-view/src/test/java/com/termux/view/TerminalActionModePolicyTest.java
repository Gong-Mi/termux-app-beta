package com.termux.view;

import android.view.ActionMode;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class TerminalActionModePolicyTest {
    @Test
    public void xiaomiManufacturerUsesPrimaryActionMode() {
        assertEquals(ActionMode.TYPE_PRIMARY,
            TerminalActionModePolicy.typeFor("Xiaomi", "Xiaomi"));
    }

    @Test
    public void redmiBrandUsesPrimaryActionMode() {
        assertEquals(ActionMode.TYPE_PRIMARY,
            TerminalActionModePolicy.typeFor("Redmi", "Redmi"));
    }

    @Test
    public void pocoBrandUsesPrimaryActionMode() {
        assertEquals(ActionMode.TYPE_PRIMARY,
            TerminalActionModePolicy.typeFor("POCO", "POCO"));
    }

    @Test
    public void nonXiaomiDeviceKeepsAospFloatingActionMode() {
        assertEquals(ActionMode.TYPE_FLOATING,
            TerminalActionModePolicy.typeFor("Google", "Pixel"));
    }

    @Test
    public void nullDeviceFieldsKeepAospFloatingActionMode() {
        assertEquals(ActionMode.TYPE_FLOATING,
            TerminalActionModePolicy.typeFor(null, null));
    }
}
