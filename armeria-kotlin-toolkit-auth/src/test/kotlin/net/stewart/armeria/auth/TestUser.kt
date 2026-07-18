package net.stewart.armeria.auth

import net.stewart.auth.AuthUser

internal data class TestUser(
    override val id: Long = 1L,
    override val username: String = "tester",
    override val passwordHash: String = "",
    override val isLocked: Boolean = false,
    override val mustChangePassword: Boolean = false,
) : AuthUser
