package io.mesazon.domain.gateway

case class ImageAsset(
    imageOriginalBucketKey: ImageOriginalBucketKey,
    imageNormalizedBucketKey: ImageNormalizedBucketKey,
    imageOriginalFileName: ImageOriginalFileName,
)
