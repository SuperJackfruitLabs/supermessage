package dev.supermessage;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.nio.charset.StandardCharsets;

/**
 * A UnifiedPush distributor reduced to the handshake
 * (unifiedpush.org/developers/spec/android): on {@code REGISTER} it answers the
 * app named in the request with {@code NEW_ENDPOINT} (the request's token,
 * {@link #ENDPOINT}), then one {@code MESSAGE} carrying a Matrix push gateway
 * body ({@link #MESSAGE}). {@code UNREGISTER} is accepted and ignored.
 *
 * <p><b>Java, not Kotlin, on purpose.</b> It is declared in the test APK's
 * manifest, so the system runs it in the test package's own process, and the
 * test APK leaves out the libraries the app already carries, Kotlin's standard
 * library among them. A Kotlin receiver died there with
 * {@code NoClassDefFoundError: kotlin/jvm/internal/Intrinsics}.
 */
public final class FakeDistributor extends BroadcastReceiver {
    public static final String REGISTER = "org.unifiedpush.android.distributor.REGISTER";

    /** {@code .invalid} never resolves (RFC 2606): this endpoint can reach no real server. */
    public static final String ENDPOINT = "https://push.fake.invalid/upFake?up=1";
    public static final String GATEWAY = "https://push.fake.invalid/_matrix/push/v1/notify";
    public static final String MESSAGE =
        "{\"notification\":{\"event_id\":\"$push1\",\"room_id\":\"!fake:x\",\"counts\":{\"unread\":1}}}";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!REGISTER.equals(intent.getAction())) return;
        String token = intent.getStringExtra("token");
        String app = intent.getStringExtra("application");
        if (token == null || app == null) return;
        context.sendBroadcast(
            new Intent("org.unifiedpush.android.connector.NEW_ENDPOINT")
                .setPackage(app)
                .putExtra("token", token)
                .putExtra("endpoint", ENDPOINT));
        context.sendBroadcast(
            new Intent("org.unifiedpush.android.connector.MESSAGE")
                .setPackage(app)
                .putExtra("token", token)
                .putExtra("bytesMessage", MESSAGE.getBytes(StandardCharsets.UTF_8)));
    }
}
