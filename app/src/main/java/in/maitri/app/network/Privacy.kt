package `in`.maitri.app.network

import okhttp3.ResponseBody
import retrofit2.http.*

/** Screen 09 "Privacy & data" and screen 14 medical notes. */
data class MedicalNotesDto(val notes: String)

interface PrivacyApi {
    @GET("api/users/me/medical-notes") suspend fun notes(@Tag expected: ExpectedAccount): MedicalNotesDto
    @PUT("api/users/me/medical-notes") suspend fun saveNotes(@Body body: MedicalNotesDto, @Tag expected: ExpectedAccount): MedicalNotesDto
    @Streaming @GET("api/users/me/export") suspend fun export(@Tag expected: ExpectedAccount): ResponseBody
    @DELETE("api/users/me") suspend fun deleteAccount(@Tag expected: ExpectedAccount): retrofit2.Response<Unit>
}

/** Public pages (docs/site, GitHub Pages) and the support address. */
object MaitriLinks {
    const val SITE = "https://ayankrmondal2003-rgb.github.io/Saathi/"
    const val PRIVACY = SITE + "privacy.html"
    const val TERMS = SITE + "terms.html"
    const val DELETE_ACCOUNT = SITE + "delete-account.html"
    const val SUPPORT_EMAIL = "ayankr.mondal6296@gmail.com"
}
