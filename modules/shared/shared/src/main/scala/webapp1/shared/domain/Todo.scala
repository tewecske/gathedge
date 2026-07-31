package webapp1.shared.domain

import zio.json.*

enum TodoStatus derives JsonCodec {
  case ToDo, InProgress, Done

  /** Click-to-move cycles forward: To Do -> In Progress -> Done -> To Do. */
  def next: TodoStatus = this match {
    case TodoStatus.ToDo       => TodoStatus.InProgress
    case TodoStatus.InProgress => TodoStatus.Done
    case TodoStatus.Done       => TodoStatus.ToDo
  }
}

object TodoStatus {
  def fromString(s: String): Option[TodoStatus] = s match {
    case "todo"        => Some(ToDo)
    case "in_progress" => Some(InProgress)
    case "done"         => Some(Done)
    case _              => None
  }

  def toDbString(status: TodoStatus): String = status match {
    case ToDo       => "todo"
    case InProgress => "in_progress"
    case Done       => "done"
  }
}

final case class TodoItem(id: Long, text: String, status: TodoStatus, createdAt: String) derives JsonCodec
