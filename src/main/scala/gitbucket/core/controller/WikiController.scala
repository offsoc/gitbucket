package gitbucket.core.controller

import gitbucket.core.model.WebHook
import gitbucket.core.model.activity.{CreateWikiPageInfo, DeleteWikiInfo, EditWikiPageInfo}
import gitbucket.core.service.RepositoryService.RepositoryInfo
import gitbucket.core.service.WebHookService.WebHookGollumPayload
import gitbucket.core.wiki.html
import gitbucket.core.service.*
import gitbucket.core.util.*
import gitbucket.core.util.StringUtil.*
import gitbucket.core.util.SyntaxSugars.*
import gitbucket.core.util.Implicits.*
import gitbucket.core.util.Directory.*
import org.scalatra.forms.*
import org.eclipse.jgit.api.Git
import org.scalatra.i18n.Messages

import scala.util.Using

class WikiController
    extends WikiControllerBase
    with WikiService
    with RepositoryService
    with AccountService
    with ActivityService
    with WebHookService
    with ReadableUsersAuthenticator
    with ReferrerAuthenticator
    with RequestCache

trait WikiControllerBase extends ControllerBase {
  self: WikiService & RepositoryService & AccountService & ActivityService & WebHookService &
    ReadableUsersAuthenticator & ReferrerAuthenticator =>

  private case class WikiPageEditForm(
    pageName: String,
    content: String,
    message: Option[String],
    currentPageName: String,
    id: String
  )

  private val newForm = mapping(
    "pageName" -> trim(label("Page name", text(required, maxlength(40), pageName, unique))),
    "content" -> trim(label("Content", text(required, conflictForNew))),
    "message" -> trim(label("Message", optional(text()))),
    "currentPageName" -> trim(label("Current page name", text())),
    "id" -> trim(label("Latest commit id", text()))
  )(WikiPageEditForm.apply)

  private val editForm = mapping(
    "pageName" -> trim(label("Page name", text(required, maxlength(40), pageName))),
    "content" -> trim(label("Content", text(required, conflictForEdit))),
    "message" -> trim(label("Message", optional(text()))),
    "currentPageName" -> trim(label("Current page name", text(required))),
    "id" -> trim(label("Latest commit id", text(required)))
  )(WikiPageEditForm.apply)

  get("/:owner/:repository/wiki")(referrersOnly { repository =>
    val branch = getWikiBranch(repository.owner, repository.name)

    getWikiPage(repository.owner, repository.name, "Home", branch).map { page =>
      html.page(
        "Home",
        page,
        getWikiPageList(repository.owner, repository.name, branch),
        repository,
        isEditable(repository),
        getWikiPage(repository.owner, repository.name, "_Sidebar", branch),
        getWikiPage(repository.owner, repository.name, "_Footer", branch)
      )
    } getOrElse redirect(s"/${repository.owner}/${repository.name}/wiki/Home/_edit")
  })

  get("/:owner/:repository/wiki/:page")(referrersOnly { repository =>
    val pageName = StringUtil.urlDecode(params("page"))
    val branch = getWikiBranch(repository.owner, repository.name)

    getWikiPage(repository.owner, repository.name, pageName, branch).map { page =>
      html.page(
        pageName,
        page,
        getWikiPageList(repository.owner, repository.name, branch),
        repository,
        isEditable(repository),
        getWikiPage(repository.owner, repository.name, "_Sidebar", branch),
        getWikiPage(repository.owner, repository.name, "_Footer", branch)
      )
    } getOrElse redirect(s"/${repository.owner}/${repository.name}/wiki/${StringUtil.urlEncode(pageName)}/_edit")
  })

  get("/:owner/:repository/wiki/:page/_history")(referrersOnly { repository =>
    val pageName = StringUtil.urlDecode(params("page"))
    val branch = getWikiBranch(repository.owner, repository.name)

    Using.resource(Git.open(getWikiRepositoryDir(repository.owner, repository.name))) { git =>
      JGitUtil.getCommitLog(git, branch, path = pageName + ".md") match {
        case Right((logs, hasNext)) => html.history(Some(pageName), logs, repository, isEditable(repository))
        case Left(_)                => NotFound()
      }
    }
  })

  private def getWikiBranch(owner: String, repository: String): String = {
    Using.resource(Git.open(Directory.getWikiRepositoryDir(owner, repository))) { git =>
      git.getRepository.getBranch
    }
  }

  get("/:owner/:repository/wiki/:page/_compare/:commitId")(referrersOnly { repository =>
    val pageName = StringUtil.urlDecode(params("page"))
    val Array(from, to) = params("commitId").split("\\.\\.\\.")

    Using.resource(Git.open(getWikiRepositoryDir(repository.owner, repository.name))) { git =>
      html.compare(
        Some(pageName),
        from,
        to,
        JGitUtil
          .getDiffs(
            git = git,
            from = Some(from),
            to = to,
            fetchContent = true,
            makePatch = false,
            maxFiles = context.settings.repositoryViewer.maxDiffFiles,
            maxLines = context.settings.repositoryViewer.maxDiffLines
          )
          .filter(_.newPath == pageName + ".md"),
        repository,
        isEditable(repository),
        flash.get("info")
      )
    }
  })

  get("/:owner/:repository/wiki/_compare/:commitId")(referrersOnly { repository =>
    val Array(from, to) = params("commitId").split("\\.\\.\\.")

    Using.resource(Git.open(getWikiRepositoryDir(repository.owner, repository.name))) { git =>
      html.compare(
        None,
        from,
        to,
        JGitUtil.getDiffs(
          git = git,
          from = Some(from),
          to = to,
          fetchContent = true,
          makePatch = false,
          maxFiles = context.settings.repositoryViewer.maxDiffFiles,
          maxLines = context.settings.repositoryViewer.maxDiffLines
        ),
        repository,
        isEditable(repository),
        flash.get("info")
      )
    }
  })

  get("/:owner/:repository/wiki/:page/_revert/:commitId")(readableUsersOnly { repository =>
    context.withLoginAccount { loginAccount =>
      if (isEditable(repository)) {
        val pageName = StringUtil.urlDecode(params("page"))
        val Array(from, to) = params("commitId").split("\\.\\.\\.")
        val branch = getWikiBranch(repository.owner, repository.name)

        if (revertWikiPage(repository.owner, repository.name, from, to, loginAccount, Some(pageName), branch)) {
          redirect(s"/${repository.owner}/${repository.name}/wiki/${StringUtil.urlEncode(pageName)}")
        } else {
          flash.update("info", "This patch was not able to be reversed.")
          redirect(
            s"/${repository.owner}/${repository.name}/wiki/${StringUtil.urlEncode(pageName)}/_compare/$from...$to"
          )
        }
      } else Unauthorized()
    }
  })

  get("/:owner/:repository/wiki/_revert/:commitId")(readableUsersOnly { repository =>
    context.withLoginAccount { loginAccount =>
      if (isEditable(repository)) {
        val Array(from, to) = params("commitId").split("\\.\\.\\.")
        val branch = getWikiBranch(repository.owner, repository.name)

        if (revertWikiPage(repository.owner, repository.name, from, to, loginAccount, None, branch)) {
          redirect(s"/${repository.owner}/${repository.name}/wiki")
        } else {
          flash.update("info", "This patch was not able to be reversed.")
          redirect(s"/${repository.owner}/${repository.name}/wiki/_compare/$from...$to")
        }
      } else Unauthorized()
    }
  })

  get("/:owner/:repository/wiki/:page/_edit")(readableUsersOnly { repository =>
    if (isEditable(repository)) {
      val pageName = StringUtil.urlDecode(params("page"))
      val branch = getWikiBranch(repository.owner, repository.name)

      html.edit(pageName, getWikiPage(repository.owner, repository.name, pageName, branch), repository)
    } else Unauthorized()
  })

  post("/:owner/:repository/wiki/_edit", editForm)(readableUsersOnly { (form, repository) =>
    context.withLoginAccount { loginAccount =>
      if (isEditable(repository)) {
        saveWikiPage(
          repository.owner,
          repository.name,
          form.currentPageName,
          form.pageName,
          appendNewLine(convertLineSeparator(form.content, "LF"), "LF"),
          loginAccount,
          form.message.getOrElse(""),
          Some(form.id)
        ).foreach { commitId =>
          updateLastActivityDate(repository.owner, repository.name)
          val wikiEditInfo =
            EditWikiPageInfo(repository.owner, repository.name, loginAccount.userName, form.pageName, commitId)
          recordActivity(wikiEditInfo)
          callWebHookOf(repository.owner, repository.name, WebHook.Gollum, context.settings) {
            getAccountByUserName(repository.owner).map { repositoryUser =>
              WebHookGollumPayload("edited", form.pageName, commitId, repository, repositoryUser, loginAccount)
            }
          }
        }
        if (notReservedPageName(form.pageName)) {
          redirect(s"/${repository.owner}/${repository.name}/wiki/${StringUtil.urlEncode(form.pageName)}")
        } else {
          redirect(s"/${repository.owner}/${repository.name}/wiki")
        }
      } else Unauthorized()
    }
  })

  get("/:owner/:repository/wiki/_new")(readableUsersOnly { repository =>
    if (isEditable(repository)) {
      html.edit("", None, repository)
    } else Unauthorized()
  })

  post("/:owner/:repository/wiki/_new", newForm)(readableUsersOnly { (form, repository) =>
    context.withLoginAccount { loginAccount =>
      if (isEditable(repository)) {
        saveWikiPage(
          repository.owner,
          repository.name,
          form.currentPageName,
          form.pageName,
          form.content,
          loginAccount,
          form.message.getOrElse(""),
          None
        ).foreach { commitId =>
          updateLastActivityDate(repository.owner, repository.name)
          val createWikiPageInfo =
            CreateWikiPageInfo(repository.owner, repository.name, loginAccount.userName, form.pageName)
          recordActivity(createWikiPageInfo)
          callWebHookOf(repository.owner, repository.name, WebHook.Gollum, context.settings) {
            getAccountByUserName(repository.owner).map { repositoryUser =>
              WebHookGollumPayload("created", form.pageName, commitId, repository, repositoryUser, loginAccount)
            }
          }
        }

        if (notReservedPageName(form.pageName)) {
          redirect(s"/${repository.owner}/${repository.name}/wiki/${StringUtil.urlEncode(form.pageName)}")
        } else {
          redirect(s"/${repository.owner}/${repository.name}/wiki")
        }
      } else Unauthorized()
    }
  })

  get("/:owner/:repository/wiki/:page/_delete")(readableUsersOnly { repository =>
    context.withLoginAccount { loginAccount =>
      if (isEditable(repository)) {
        val pageName = StringUtil.urlDecode(params("page"))
        deleteWikiPage(
          repository.owner,
          repository.name,
          pageName,
          loginAccount.fullName,
          loginAccount.mailAddress,
          s"Destroyed $pageName"
        )
        val deleteWikiInfo = DeleteWikiInfo(
          repository.owner,
          repository.name,
          loginAccount.userName,
          pageName
        )
        recordActivity(deleteWikiInfo)
        updateLastActivityDate(repository.owner, repository.name)

        redirect(s"/${repository.owner}/${repository.name}/wiki")
      } else Unauthorized()
    }
  })

  get("/:owner/:repository/wiki/_pages")(referrersOnly { repository =>
    val branch = getWikiBranch(repository.owner, repository.name)
    html.pages(getWikiPageList(repository.owner, repository.name, branch), repository, isEditable(repository))
  })

  get("/:owner/:repository/wiki/_history")(referrersOnly { repository =>
    Using.resource(Git.open(getWikiRepositoryDir(repository.owner, repository.name))) { git =>
      JGitUtil.getCommitLog(git, "master") match {
        case Right((logs, hasNext)) => html.history(None, logs, repository, isEditable(repository))
        case Left(_)                => NotFound()
      }
    }
  })

  get("/:owner/:repository/wiki/_blob/*")(referrersOnly { repository =>
    val path = multiParams("splat").head
    Using.resource(Git.open(getWikiRepositoryDir(repository.owner, repository.name))) { git =>
      val revCommit = JGitUtil.getRevCommitFromId(git, git.getRepository.resolve("master"))

      getPathObjectId(git, path, revCommit).map { objectId =>
        responseRawFile(git, objectId, path, repository)
      } getOrElse NotFound()
    }
  })

  private def unique: Constraint = new Constraint() {
    override def validate(
      name: String,
      value: String,
      params: Map[String, Seq[String]],
      messages: Messages
    ): Option[String] = {
      val owner = params.value("owner")
      val repository = params.value("repository")
      val branch = getWikiBranch(owner, repository)

      getWikiPageList(owner, repository, branch)
        .find(_ == value)
        .map(_ => "Page already exists.")
    }
  }

  private def pageName: Constraint = new Constraint() {
    override def validate(name: String, value: String, messages: Messages): Option[String] =
      if (value.exists("\\/:*?\"<>|".contains(_))) {
        Some(s"$name contains invalid character.")
      } else if (notReservedPageName(value) && (value.startsWith("_") || value.startsWith("-"))) {
        Some(s"$name starts with invalid character.")
      } else {
        None
      }
  }

  private def notReservedPageName(value: String): Boolean = !(Array[String]("_Sidebar", "_Footer") contains value)

  private def conflictForNew: Constraint = new Constraint() {
    override def validate(name: String, value: String, messages: Messages): Option[String] = {
      targetWikiPage.map { _ =>
        "Someone has created the wiki since you started. Please reload this page and re-apply your changes."
      }
    }
  }

  private def conflictForEdit: Constraint = new Constraint() {
    override def validate(name: String, value: String, messages: Messages): Option[String] = {
      targetWikiPage.filter(_.id != params("id")).map { _ =>
        "Someone has edited the wiki since you started. Please reload this page and re-apply your changes."
      }
    }
  }

  private def targetWikiPage: Option[WikiService.WikiPageInfo] = {
    val owner = params("owner")
    val repository = params("repository")
    val pageName = params("pageName")
    val branch = getWikiBranch(owner, repository)
    getWikiPage(owner, repository, pageName, branch)
  }

  private def isEditable(repository: RepositoryInfo)(implicit context: Context): Boolean = {
    repository.repository.options.wikiOption match {
      case "ALL"     => !repository.repository.isPrivate && context.loginAccount.isDefined
      case "PUBLIC"  => hasGuestRole(repository.owner, repository.name, context.loginAccount)
      case "PRIVATE" => hasDeveloperRole(repository.owner, repository.name, context.loginAccount)
      case "DISABLE" => false
    }
  }

}
