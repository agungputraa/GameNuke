package anehprodns;

import go.Seq;

public final class Tunnel implements Seq.Proxy {
    private final int refnum;

    private static native int __New();

    public native void stop();

    static {
        Anehprodns.touch();
    }

    @Override
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    Tunnel(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }

    public Tunnel() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof Tunnel)) {
            return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        return System.identityHashCode(this);
    }

    @Override
    public String toString() {
        return "Tunnel{refnum:" + this.refnum + "}";
    }
}
