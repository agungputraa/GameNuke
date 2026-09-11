package anehprodns;

import go.Seq;

public abstract class Anehprodns {
    private static native void _init();

    public static native Tunnel start(long j, Config config, Protector protector, EventListener eventListener);

    public static void touch() {
    }

    static {
        Seq.touch();
        _init();
    }

    private Anehprodns() {
    }

    static final class proxyEventListener implements EventListener, Seq.Proxy {
        private final int refnum;

        @Override
        public native void onEvent(String str, String str2);

        @Override
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        proxyEventListener(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }
    }

    static final class proxyProtector implements Protector, Seq.Proxy {
        private final int refnum;

        @Override
        public native boolean protect(long j);

        @Override
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        proxyProtector(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }
    }
}
