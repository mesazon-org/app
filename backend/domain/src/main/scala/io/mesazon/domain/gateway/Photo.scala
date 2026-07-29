package io.mesazon.domain.gateway

case class Photo(
    originalBucketKey: PhotoOriginalBucketKey,
    normalizedBucketKey: PhotoNormalizedBucketKey,
    originalFileName: PhotoOriginalFileName,
)
