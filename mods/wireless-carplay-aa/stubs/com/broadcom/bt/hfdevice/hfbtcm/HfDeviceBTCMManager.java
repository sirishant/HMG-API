package com.broadcom.bt.hfdevice.hfbtcm;

import android.bluetooth.BluetoothDevice;

public class HfDeviceBTCMManager {
    public void registerHfDeviceStatusListener(HfDeviceStatusListener listener) {}

    public BluetoothDevice IbtcmGetHfpConnectedDevice() {
        return null;
    }

    public boolean IbtcmIsSppConnectedDevice(String address) {
        return false;
    }

    public boolean IbtcmSppConnect(String address) {
        return false;
    }

    public boolean IbtcmSppOutPutStreamWrite(String address, byte[] buffer) {
        return false;
    }

    public BluetoothDevice IbtcmGetConnectedDevice1() {
        return null;
    }

    public BluetoothDevice IbtcmGetConnectedDevice2() {
        return null;
    }

    /** HFP, A2DP, AVRCP */
    public boolean[] IbtcmGetConnectedProfilesAll(BluetoothDevice device) {
        return null;
    }

    public boolean IbtcmSppDisconnect(String address) {
        return false;
    }
}
