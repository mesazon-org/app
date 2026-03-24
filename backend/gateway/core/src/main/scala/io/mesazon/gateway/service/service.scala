package io.mesazon.gateway.service

import io.mesazon.domain.gateway.ServiceError
import zio.*

type ServiceTask[A] = IO[ServiceError, A]
