package com.ranobe.audioreader.domain.audio

import kotlinx.coroutines.sync.Mutex

object AudioWorkCoordinator {
    val mutex = Mutex()
}
