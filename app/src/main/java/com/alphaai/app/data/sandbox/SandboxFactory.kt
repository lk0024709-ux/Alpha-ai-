package com.alphaai.app.data.sandbox

import com.alphaai.app.domain.model.SandboxProfile
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SandboxFactory @Inject constructor(
    private val local: LocalLightSandbox,
    private val remoteFactory: RemoteSandbox.Factory
) {
    fun create(profile: SandboxProfile): SandboxClient = when (profile) {
        SandboxProfile.LIGHT -> local
        SandboxProfile.REMOTE -> remoteFactory.create()
    }
}
