package webapp1.backend.db

import zio.*

trait TodoRepository {
  def insert(userId: Long, text: String, status: String, createdAt: Long): Task[TodoItemRow]
  def listForUser(userId: Long): Task[List[TodoItemRow]]
  /** Updates status only if the row exists and belongs to `userId`. Returns the
    * updated row, or None if not found/not owned.
    */
  def updateStatus(id: Long, userId: Long, status: String): Task[Option[TodoItemRow]]
}
