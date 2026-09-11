package anehprodns;

import java.util.Arrays;
import go.Seq;

public final class Config implements Seq.Proxy {
    private final int refnum;

    private static native int __New();

    public native long getDNSIdleTimeoutMillis();

    public native long getDefaultIdleTimeoutMillis();

    public native String getFakeDNSHost();

    public native String getFakeDNSHostV6();

    public native long getFakeDNSPort();

    public native long getMTU();

    public native String getUpstreamDNSHost();

    public native String getUpstreamDNSHostV6();

    public native long getUpstreamDNSPort();

    public native void setDNSIdleTimeoutMillis(long j);

    public native void setDefaultIdleTimeoutMillis(long j);

    public native void setFakeDNSHost(String str);

    public native void setFakeDNSHostV6(String str);

    public native void setFakeDNSPort(long j);

    public native void setMTU(long j);

    public native void setUpstreamDNSHost(String str);

    public native void setUpstreamDNSHostV6(String str);

    public native void setUpstreamDNSPort(long j);

    static {
        Anehprodns.touch();
    }

    @Override
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    Config(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }

    public Config() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof Config)) {
            return false;
        }
        Config config = (Config) obj;
        String fakeDNSHost = getFakeDNSHost();
        String fakeDNSHost2 = config.getFakeDNSHost();
        if (fakeDNSHost == null) {
            if (fakeDNSHost2 != null) {
                return false;
            }
        } else if (!fakeDNSHost.equals(fakeDNSHost2)) {
            return false;
        }
        if (getFakeDNSPort() != config.getFakeDNSPort()) {
            return false;
        }
        String upstreamDNSHost = getUpstreamDNSHost();
        String upstreamDNSHost2 = config.getUpstreamDNSHost();
        if (upstreamDNSHost == null) {
            if (upstreamDNSHost2 != null) {
                return false;
            }
        } else if (!upstreamDNSHost.equals(upstreamDNSHost2)) {
            return false;
        }
        if (getUpstreamDNSPort() != config.getUpstreamDNSPort()) {
            return false;
        }
        String fakeDNSHostV6 = getFakeDNSHostV6();
        String fakeDNSHostV7 = config.getFakeDNSHostV6();
        if (fakeDNSHostV6 == null) {
            if (fakeDNSHostV7 != null) {
                return false;
            }
        } else if (!fakeDNSHostV6.equals(fakeDNSHostV7)) {
            return false;
        }
        String upstreamDNSHostV6 = getUpstreamDNSHostV6();
        String upstreamDNSHostV7 = config.getUpstreamDNSHostV6();
        if (upstreamDNSHostV6 == null) {
            if (upstreamDNSHostV7 != null) {
                return false;
            }
        } else if (!upstreamDNSHostV6.equals(upstreamDNSHostV7)) {
            return false;
        }
        return getMTU() == config.getMTU() && getDNSIdleTimeoutMillis() == config.getDNSIdleTimeoutMillis() && getDefaultIdleTimeoutMillis() == config.getDefaultIdleTimeoutMillis();
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[]{getFakeDNSHost(), Long.valueOf(getFakeDNSPort()), getUpstreamDNSHost(), Long.valueOf(getUpstreamDNSPort()), getFakeDNSHostV6(), getUpstreamDNSHostV6(), Long.valueOf(getMTU()), Long.valueOf(getDNSIdleTimeoutMillis()), Long.valueOf(getDefaultIdleTimeoutMillis())});
    }

    @Override
    public String toString() {
        return "Config{FakeDNSHost:" + getFakeDNSHost() + ",FakeDNSPort:" + getFakeDNSPort() + ",UpstreamDNSHost:" + getUpstreamDNSHost() + ",UpstreamDNSPort:" + getUpstreamDNSPort() + ",FakeDNSHostV6:" + getFakeDNSHostV6() + ",UpstreamDNSHostV6:" + getUpstreamDNSHostV6() + ",MTU:" + getMTU() + ",DNSIdleTimeoutMillis:" + getDNSIdleTimeoutMillis() + ",DefaultIdleTimeoutMillis:" + getDefaultIdleTimeoutMillis() + "}";
    }
}
