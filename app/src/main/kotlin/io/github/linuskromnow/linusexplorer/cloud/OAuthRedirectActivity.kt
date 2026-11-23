package io.github.linuskromnow.linusexplorer.cloud

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import io.github.linuskromnow.linusexplorer.LinusExplorerApp
import io.github.linuskromnow.linusexplorer.MainActivity

/** Receives the browser's return from a cloud sign-in page and brings the app back to the front. */
class OAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let { (application as LinusExplorerApp).container.cloudSignIn.complete(it) }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
