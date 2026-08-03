package io.mesazon.domain.gateway

case class ImageAsset(
    imageOriginalS3BucketKey: ImageOriginalS3BucketKey,
    imageNormalizedS3BucketKey: ImageNormalizedS3BucketKey,
    imageOriginalFileName: ImageOriginalFileName,
)
