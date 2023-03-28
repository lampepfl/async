package concurrent

/** A trait for cancellable entities that can be grouped */
trait Cancellable:

  private var group: CompletionGroup = CompletionGroup.Unlinked

  /** Issue a cancel request */
  def cancel(): Unit

  /** Add this cancellable to the given group after removing
   *  it from the previous group in which it was.
   */
  def link(group: CompletionGroup): this.type =
    this.group.drop(this)
    this.group = group
    this.group.add(this)
    this

  /** Link this cancellable to the cancellable group of the
   *  current async context.
   */
  def link()(using async: Async): this.type =
    link(async.group)

  /** Unlink this cancellable from its group. */
  def unlink(): this.type =
    link(CompletionGroup.Unlinked)

  /** Signal completion of this cancellable to its group. */
  def signalCompletion()(using async: Async): this.type =
    async.group.handleCompletion(this)
    this


end Cancellable
