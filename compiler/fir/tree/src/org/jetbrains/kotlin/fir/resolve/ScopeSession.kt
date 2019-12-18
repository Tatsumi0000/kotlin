/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve

import org.jetbrains.kotlin.fir.scopes.FirScope


abstract class ScopeSessionKey<Key : Any, Scope : FirScope>()

class ScopeSession {
    private val scopes = hashMapOf<Any, HashMap<ScopeSessionKey<*, *>, FirScope>>()

    @Deprecated(level = DeprecationLevel.ERROR, message = "Only for getOrBuild")
    fun scopes() = scopes

    inline fun <Key : Any, Scope : FirScope> getOrBuild(symbol: Key, key: ScopeSessionKey<Key, Scope>, build: () -> Scope): Scope {

        @Suppress("DEPRECATION_ERROR")
        return scopes().getOrPut(symbol) {
            hashMapOf()
        }.getOrPut(key) {
            build()
        } as Scope
    }
}

