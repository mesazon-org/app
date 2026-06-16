package io.mesazon.gateway.tapir

import io.mesazon.domain.gateway.TapirServerError
import sttp.model.StatusCode
import zio.*

type TapirTask[A] = IO[(StatusCode, TapirServerError), A]
