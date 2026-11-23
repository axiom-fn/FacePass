package com.cc.facepass.judgment

import com.cc.facepass.substance.Request

/** A rule: a premise about requests. Of a given request it affirms a verdict, or says nothing. */
fun interface Rule {
    fun judge(request: Request): Verdict?
}
