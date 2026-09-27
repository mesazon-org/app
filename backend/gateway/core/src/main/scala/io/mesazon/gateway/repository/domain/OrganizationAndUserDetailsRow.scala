package io.mesazon.gateway.repository.domain

import io.mesazon.domain.gateway.*

case class OrganizationAndUserDetailsRow(
    organizationID: OrganizationID,
    name: OrganizationName,
    slug: OrganizationSlug,
    userRole: OrganizationUserRole,
    logoImageNormalizedS3BucketKey: Option[ImageNormalizedS3BucketKey],
)
