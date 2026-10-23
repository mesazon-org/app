package io.mesazon.gateway.json

import sttp.apispec.Schema as ApiSchema

trait OpenAIJsonSchema[A] {
  def schema: ApiSchema
}
