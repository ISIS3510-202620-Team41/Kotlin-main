package com.group41.kotlinapp.viewmodel

import com.group41.kotlinapp.network.UserDto
import com.group41.kotlinapp.repository.AuthRepository
import com.group41.kotlinapp.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import retrofit2.HttpException
import retrofit2.Response

/** viewModelScope corre en Dispatchers.Main, que no existe en la JVM: se reemplaza en los tests */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(dispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}

val testUser = UserDto(
    id = "11111111-1111-1111-1111-111111111111",
    email = "ana@test.com",
    name = "Ana Pérez",
    bio = null,
    avatarUrl = null
)

/** Error HTTP como lo lanza Retrofit, con el cuerpo ApiError del backend */
fun httpError(code: Int, message: String = "error") = HttpException(
    Response.error<Any>(
        code,
        """{"status":$code,"message":"$message"}""".toResponseBody("application/json".toMediaType())
    )
)

class FakeAuthRepository : AuthRepository {
    var session = true
    var failure: Exception? = null
    val logins = mutableListOf<Pair<String, String>>()
    val registrations = mutableListOf<Triple<String, String, String>>()
    var logoutCalls = 0
    var clearCalls = 0

    override fun hasSession() = session

    override suspend fun login(email: String, password: String): UserDto {
        logins += email to password
        failure?.let { throw it }
        return testUser
    }

    override suspend fun register(name: String, email: String, password: String): UserDto {
        registrations += Triple(name, email, password)
        failure?.let { throw it }
        return testUser
    }

    val googleTokens = mutableListOf<String>()

    override suspend fun loginWithGoogle(idToken: String): UserDto {
        googleTokens += idToken
        failure?.let { throw it }
        return testUser
    }

    override suspend fun logout() {
        logoutCalls++
    }

    override fun clearSession() {
        clearCalls++
    }
}

class FakeUserRepository : UserRepository {
    var user = testUser
    var failure: Exception? = null
    var meCalls = 0
    val updates = mutableListOf<Pair<String, String>>()

    override suspend fun me(): UserDto {
        meCalls++
        failure?.let { throw it }
        return user
    }

    override suspend fun updateProfile(name: String, bio: String): UserDto {
        updates += name to bio
        failure?.let { throw it }
        user = user.copy(name = name, bio = bio)
        return user
    }

    override suspend fun uploadAvatar(prepare: () -> ByteArray): UserDto {
        prepare()
        failure?.let { throw it }
        user = user.copy(avatarUrl = "http://localhost:8080/uploads/nueva.jpg")
        return user
    }

    override suspend fun deleteAvatar(): UserDto {
        failure?.let { throw it }
        user = user.copy(avatarUrl = null)
        return user
    }
}
