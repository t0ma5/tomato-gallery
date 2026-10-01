package tomato.gallery.helpers

import android.content.Intent
import java.security.SecureRandom

object InternalNonce {
    val value: Long = SecureRandom().nextLong()
}

fun Intent.putInternalNonce(): Intent {
    putExtra(INTERNAL_NONCE, InternalNonce.value)
    return this
}

fun Intent.isTrustedInternal(): Boolean {
    return getLongExtra(INTERNAL_NONCE, 0L) == InternalNonce.value
}
