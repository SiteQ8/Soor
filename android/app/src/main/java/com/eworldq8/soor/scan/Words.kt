package com.eworldq8.soor.scan

/** Numbers in Arabic take different words by count, so each sentence is written out whole. */
object Words {
    fun found(n: Int, ar: Boolean): String = if (!ar) when (n) {
        0 -> "Soor found no other devices on your network"
        1 -> "Soor found 1 device on your network"
        else -> "Soor found $n devices on your network"
    } else when {
        n == 0 -> "لم يجد سُور أجهزة أخرى على شبكتك"
        n == 1 -> "وجد سُور جهازًا واحدًا على شبكتك"
        n == 2 -> "وجد سُور جهازين على شبكتك"
        n in 3..10 -> "وجد سُور $n أجهزة على شبكتك"
        else -> "وجد سُور $n جهازًا على شبكتك"
    }

    fun newOnes(n: Int, ar: Boolean): String = if (!ar) {
        if (n == 1) "1 of them is new" else "$n of them are new"
    } else when {
        n == 1 -> "منها جهاز جديد لم يظهر من قبل"
        n == 2 -> "منها جهازان جديدان لم يظهرا من قبل"
        n in 3..10 -> "منها $n أجهزة جديدة لم تظهر من قبل"
        else -> "منها $n جهازًا جديدًا"
    }
}
