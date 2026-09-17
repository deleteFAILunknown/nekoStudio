package com.flyfishxu.kadb.cert

data class KadbCertPolicy(
    val keySizeBits: Int = 2048,
    val certValidityDays: Int = 3650,
    val autoHealInvalidPrivateKey: Boolean = true,
    val subject: Subject = Subject()
) {
    data class Subject(
        val cn: String = "kadb",
        val ou: String = "kadb",
        val o: String = "kadb",
        val l: String = "kadb",
        val st: String = "kadb",
        val c: String = "US"
    )
}
