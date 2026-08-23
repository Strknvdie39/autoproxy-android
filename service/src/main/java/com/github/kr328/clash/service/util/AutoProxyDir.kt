package com.github.kr328.clash.service.util

import android.content.Context
import java.io.File

/** AutoProxy: thu muc chua config.yaml co dinh (spec D' 3.4). Xoa khi DISCONNECT. */
val Context.autoproxyDir: File
    get() = filesDir.resolve("autoproxy")
