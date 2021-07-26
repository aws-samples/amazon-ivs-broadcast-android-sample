package com.amazonaws.ivs.basicbroadcast.common

import android.content.Context
import com.amazonaws.ivs.basicbroadcast.R
import com.amazonaws.ivs.broadcast.BroadcastSession.listAvailableDevices
import com.amazonaws.ivs.broadcast.Device

fun Context.getMicrophoneItems(): List<String> {
    val list = mutableListOf<String>()
    for (item in getSortedMicrophoneList()) {
        list.add(getString(R.string.txt_microphone_device_template, item.deviceId, item.position))
    }
    return list
}

fun Context.getCameraItems(): List<String> {
    val list = mutableListOf<String>()
    for (item in getSortedCameraList()) {
        list.add(getString(R.string.txt_camera_device_template, item.deviceId, item.position))
    }
    return list
}

fun Context.getSelectedCamera(position: Int): Device.Descriptor =
    getSortedCameraList()[position]

fun Context.getSelectedMicrophone(position: Int): Device.Descriptor =
    getSortedMicrophoneList()[position]

fun Context.getSortedCameraList(): List<Device.Descriptor> =
    listAvailableDevices(this).sortedBy { it.deviceId }.filter { it.type == Device.Descriptor.DeviceType.CAMERA }

fun Context.getSortedMicrophoneList(): List<Device.Descriptor> =
    listAvailableDevices(this).sortedBy { it.deviceId }.filter { it.type == Device.Descriptor.DeviceType.MICROPHONE }

fun Context.getAvailableCameraSize(): Int =
    listAvailableDevices(this).filter { it.type == Device.Descriptor.DeviceType.CAMERA }.size

fun Context.getAvailableMicrophoneSize(): Int =
    listAvailableDevices(this).filter { it.type == Device.Descriptor.DeviceType.MICROPHONE }.size

fun Device.Descriptor.isExternal(): Boolean =
    position == Device.Descriptor.Position.USB || position == Device.Descriptor.Position.BLUETOOTH || position == Device.Descriptor.Position.AUX

