package com.djmarino.atoto.x10adb;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Minimal client for the FYT main-module system-property bridge found on the
 * tested ATOTO S8. This is vendor-specific and intentionally has no fallback
 * to root, boot-image patching, or shell privilege escalation.
 *
 * S8G1104MS note: checked against com.syu.ms 25.1119 from the S8G1104MS
 * firmware (APP20251124 / System20251117). Main module 0 command 161 calls
 * SystemProperties.set(strings[0], strings[1]), but only when both strings are
 * non-empty, so a property cannot be cleared with "". The UI still reads the
 * properties back after every request in case the unit runs other firmware.
 */
final class FytAdbController implements ServiceConnection {
    interface Listener {
        void onRequestSent();
        void onError(String message);
    }

    private enum Request {
        TEMPORARY,
        PERSISTENT,
        DISABLE,
        USB_DEBUGGING_AT_BOOT_ON,
        USB_DEBUGGING_AT_BOOT_OFF
    }

    private static final String TOOLKIT_PACKAGE = "com.syu.ms";
    private static final String TOOLKIT_SERVICE = "app.ToolkitService";
    private static final String TOOLKIT_DESCRIPTOR = "com.syu.ipc.IRemoteToolkit";
    private static final String MODULE_DESCRIPTOR = "com.syu.ipc.IRemoteModule";
    private static final int MAIN_MODULE = 0;
    private static final int SET_ANY_SYSTEM_PROPERTY = 161;
    static final int ADB_PORT = 5555;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private IBinder mainModule;
    private Listener pendingListener;
    private Request pendingRequest;
    private boolean bound;

    FytAdbController(Context context) {
        this.context = context.getApplicationContext();
    }

    void enableTemporary(Listener listener) {
        request(Request.TEMPORARY, listener);
    }

    void enablePersistent(Listener listener) {
        request(Request.PERSISTENT, listener);
    }

    void disablePersistent(Listener listener) {
        request(Request.DISABLE, listener);
    }

    /**
     * Android 10's AdbService copies persist.sys.usb.config into the
     * adb_enabled setting at boot. adb_enabled must be 1 for the RSA prompt to
     * appear, so this is the fallback when the Developer options toggle will
     * not stay on. Takes effect after a reboot.
     */
    void setUsbDebuggingAtBoot(boolean enabled, Listener listener) {
        request(enabled ? Request.USB_DEBUGGING_AT_BOOT_ON
                : Request.USB_DEBUGGING_AT_BOOT_OFF, listener);
    }

    boolean isToolkitServicePresent() {
        Intent intent = toolkitIntent();
        return context.getPackageManager().resolveService(intent, 0) != null;
    }

    private void request(Request request, Listener listener) {
        if (pendingListener != null) {
            listener.onError("Another request is already in progress.");
            return;
        }
        pendingListener = listener;
        pendingRequest = request;
        if (mainModule != null && mainModule.isBinderAlive()) {
            runPendingRequest();
            return;
        }

        try {
            bound = context.bindService(toolkitIntent(), this, Context.BIND_AUTO_CREATE);
            if (!bound) {
                fail("The head unit's system service did not respond. This firmware may be incompatible.");
            }
        } catch (Exception error) {
            fail("Could not reach the head unit's system service: " + safeMessage(error));
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder service) {
        try {
            mainModule = getRemoteModule(service, MAIN_MODULE);
            if (mainModule == null) {
                fail("The head unit's system service was unavailable.");
                return;
            }
            runPendingRequest();
        } catch (Exception error) {
            fail("Connection to the head unit's system service failed: " + safeMessage(error));
        }
    }

    @Override
    public void onNullBinding(ComponentName name) {
        // No onServiceConnected follows, so the request would otherwise never finish.
        unbind();
        fail("The head unit's system service refused the connection. This firmware may be incompatible.");
    }

    @Override
    public void onBindingDied(ComponentName name) {
        // This binding never reconnects; drop it so the next request binds afresh.
        unbind();
        fail("The head unit's system service stopped. Try again.");
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        mainModule = null;
        // The ServiceConnection remains registered and may reconnect. Keep
        // `bound` true so close() still performs the matching unbindService().
    }

    void close() {
        pendingListener = null;
        pendingRequest = null;
        unbind();
    }

    private void unbind() {
        if (bound) {
            try {
                context.unbindService(this);
            } catch (Exception ignored) {
                // The service may already have disconnected during shutdown.
            }
        }
        bound = false;
        mainModule = null;
    }

    private void runPendingRequest() {
        Request request = pendingRequest;
        if (request == null) {
            return;
        }
        try {
            switch (request) {
                case TEMPORARY:
                    // Clear the persistent port too, so "until reboot" also
                    // means that when the unit was set to stay on.
                    setProperty("persist.adb.tcp.port", "-1");
                    setProperty("service.adb.tcp.port", Integer.toString(ADB_PORT));
                    break;
                case PERSISTENT:
                    setProperty("persist.adb.tcp.port", Integer.toString(ADB_PORT));
                    setProperty("service.adb.tcp.port", Integer.toString(ADB_PORT));
                    break;
                case DISABLE:
                    // "" is ignored by command 161; adbd treats any port <= 0 as off.
                    setProperty("persist.adb.tcp.port", "-1");
                    setProperty("service.adb.tcp.port", "-1");
                    break;
                case USB_DEBUGGING_AT_BOOT_ON:
                    setProperty("persist.sys.usb.config", "adb");
                    succeed();
                    return;
                case USB_DEBUGGING_AT_BOOT_OFF:
                    setProperty("persist.sys.usb.config", "none");
                    succeed();
                    return;
            }
            mainHandler.postDelayed(() -> {
                try {
                    setProperty("ctl.restart", "adbd");
                    succeed();
                } catch (Exception error) {
                    fail("Restarting ADB failed: " + safeMessage(error));
                }
            }, 400L);
        } catch (Exception error) {
            fail("The request failed: " + safeMessage(error));
        }
    }

    private Intent toolkitIntent() {
        Intent intent = new Intent();
        intent.setClassName(TOOLKIT_PACKAGE, TOOLKIT_SERVICE);
        return intent;
    }

    private void setProperty(String name, String value) throws RemoteException {
        IBinder module = mainModule;
        if (module == null || !module.isBinderAlive()) {
            throw new RemoteException("The system service disconnected");
        }

        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(MODULE_DESCRIPTOR);
            data.writeInt(SET_ANY_SYSTEM_PROPERTY);
            data.writeIntArray(new int[]{0, 0});
            data.writeFloatArray(null);
            data.writeStringArray(new String[]{name, value});
            if (!module.transact(1, data, null, IBinder.FLAG_ONEWAY)) {
                throw new RemoteException("The property request was rejected");
            }
        } finally {
            data.recycle();
        }
    }

    private static IBinder getRemoteModule(IBinder toolkit, int module)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(TOOLKIT_DESCRIPTOR);
            data.writeInt(module);
            if (!toolkit.transact(1, data, reply, 0)) {
                throw new RemoteException("The system service rejected the request");
            }
            reply.readException();
            return reply.readStrongBinder();
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    private void succeed() {
        Listener listener = pendingListener;
        pendingListener = null;
        pendingRequest = null;
        if (listener != null) {
            listener.onRequestSent();
        }
    }

    private void fail(String message) {
        Listener listener = pendingListener;
        pendingListener = null;
        pendingRequest = null;
        if (listener != null) {
            listener.onError(message);
        }
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }
}
