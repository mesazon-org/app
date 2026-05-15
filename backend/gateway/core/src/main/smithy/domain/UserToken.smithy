$version: "2.0"

namespace io.mesazon.gateway.smithy

structure TokenRefreshPostRequest {
    @required
    refreshToken: String
}

structure TokenRefreshPostResponse {
    @required
    refreshToken: String
    @required
    accessToken: String
    @required
    accessTokenExpiresInSeconds: Long
}
