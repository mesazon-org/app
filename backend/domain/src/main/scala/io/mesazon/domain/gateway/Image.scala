package io.mesazon.domain.gateway

case class Image(
    originalBucketKey: ImageOriginalBucketKey,
    normalizedBucketKey: ImageNormalizedBucketKey,
    originalFileName: ImageOriginalFileName,
)
