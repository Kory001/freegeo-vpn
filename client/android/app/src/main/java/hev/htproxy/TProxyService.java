package hev.htproxy;

public class TProxyService {
    static {
        try {
            System.loadLibrary("hev-socks5-tunnel");
        } catch (UnsatisfiedLinkError e1) {
            try {
                System.loadLibrary("tun2socks");
            } catch (UnsatisfiedLinkError e2) {
                android.util.Log.w("TProxyService", "Failed to load hev lib: " + e1.getMessage() + " / " + e2.getMessage());
            }
        }
    }

    public static native boolean TProxyStartService(String configPath, int fd);
    public static native boolean TProxyStopService();
    public static native boolean TProxyIsRunning();
    public static native long[] TProxyGetStats();
}
