package webapp1.shared.dto

import zio.json.*
import webapp1.shared.domain.TodoStatus

final case class CreateTodoRequest(text: String) derives JsonCodec
final case class UpdateTodoStatusRequest(status: TodoStatus) derives JsonCodec
