package com.rad.shifttracker.data.backup

import android.accounts.Account
import android.content.Context
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.common.api.Scope

/**
 * Jedino mesto koje još čita zastareli GoogleSignIn: nalog prijavljen u verzijama pre prelaska na
 * AuthorizationClient, da korisnik posle ažuriranja ne mora ponovo da se prijavi. Čita samo
 * lokalno sačuvan nalog (bez mreže). Obrisati kad sve instalacije pređu na novu verziju.
 */
@Suppress("DEPRECATION")
internal fun legacySignedInAccount(context: Context, scope: Scope): Account? = runCatching {
    val account = GoogleSignIn.getLastSignedInAccount(context) ?: return null
    account.account?.takeIf { GoogleSignIn.hasPermissions(account, scope) }
}.getOrNull()
