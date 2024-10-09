/*
 *************************************************************************************
 * Copyright 2011 Normation SAS
 *************************************************************************************
 *
 * This file is part of Rudder.
 *
 * Rudder is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * In accordance with the terms of section 7 (7. Additional Terms.) of
 * the GNU General Public License version 3, the copyright holders add
 * the following Additional permissions:
 * Notwithstanding to the terms of section 5 (5. Conveying Modified Source
 * Versions) and 6 (6. Conveying Non-Source Forms.) of the GNU General
 * Public License version 3, when you create a Related Module, this
 * Related Module is not considered as a part of the work and may be
 * distributed under the license agreement of your choice.
 * A "Related Module" means a set of sources files including their
 * documentation that, without modification of the Source Code, enables
 * supplementary functions or services in addition to those offered by
 * the Software.
 *
 * Rudder is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Rudder.  If not, see <http://www.gnu.org/licenses/>.

 *
 *************************************************************************************
 */

package com.normation.rudder.web.comet

import bootstrap.liftweb.FindCurrentUser
import bootstrap.liftweb.RudderConfig
import bootstrap.liftweb.RudderConfig.clearCacheService
import com.normation.inventory.domain.NodeId
import com.normation.rudder.AuthorizationType
import com.normation.rudder.batch.*
import com.normation.rudder.domain.nodes.NodeGroup
import com.normation.rudder.domain.nodes.NodeGroupId
import com.normation.rudder.domain.properties.FailedNodePropertyHierarchy
import com.normation.rudder.domain.properties.ResolvedNodePropertyHierarchy
import com.normation.rudder.domain.properties.SuccessNodePropertyHierarchy
import com.normation.rudder.facts.nodes.MinimalNodeFactInterface
import com.normation.rudder.facts.nodes.QueryContext
import com.normation.rudder.ncf.CompilationStatus
import com.normation.rudder.ncf.CompilationStatusAllSuccess
import com.normation.rudder.ncf.CompilationStatusErrors
import com.normation.rudder.ncf.EditorTechniqueError
import com.normation.rudder.users.CurrentUser
import com.normation.rudder.users.RudderUserDetail
import com.normation.rudder.web.snippet.WithNonce
import com.normation.utils.DateFormaterService
import com.normation.zio.UnsafeRun
import net.liftweb.common.*
import net.liftweb.http.*
import net.liftweb.http.js.*
import net.liftweb.http.js.JE.*
import net.liftweb.http.js.JsCmds.*
import org.apache.commons.text.StringEscapeUtils
import org.joda.time.DateTime
import scala.util.matching.Regex
import scala.xml.*

class AsyncDeployment extends CometActor with CometListener with Loggable {
  import AsyncDeployment.*

  private val asyncDeploymentAgent = RudderConfig.asyncDeploymentAgent
  private val nodeFactRepo         = RudderConfig.nodeFactRepository
  private val nodeGroupRepo        = RudderConfig.roNodeGroupRepository
  private val linkUtil             = RudderConfig.linkUtil

  // current states of the deployment
  private var deploymentStatus = DeploymentStatus(NoStatus, IdleDeployer)
  private var compilationStatus:         CompilationStatus                                                                               = CompilationStatusAllSuccess
  private var nodeProperties:            Map[NodeId, ResolvedNodePropertyHierarchy]                                                      = Map.empty
  private var groupProperties:           Map[NodeGroupId, ResolvedNodePropertyHierarchy]                                                 = Map.empty
  private def globalStatus:              (CurrentDeploymentStatus, CompilationStatus, NodeConfigurationStatus, GroupConfigurationStatus) = {
    (
      deploymentStatus.current,
      compilationStatus,
      NodeConfigurationStatus.fromProperties(nodeProperties),
      GroupConfigurationStatus.fromProperties(groupProperties)
    )
  }
  // we need to get current user from SpringSecurity because it is not set in session anymore,
  // and comet doesn't know about requests
  private val currentUser:               Option[RudderUserDetail]                                                                        = FindCurrentUser.get()
  def havePerm(perm: AuthorizationType): Boolean                                                                                         = {
    currentUser match {
      case None    => false
      case Some(u) => u.checkRights(perm)
    }
  }

  override def registerWith: AsyncDeploymentActor = asyncDeploymentAgent

  override val defaultHtml = NodeSeq.Empty

  override def lowPriority: PartialFunction[Any, Unit] = {
    case msg: AsyncDeploymentActorCreateUpdate =>
      deploymentStatus = msg.deploymentStatus
      compilationStatus = msg.compilationStatus
      nodeProperties = msg.nodeProperties
      groupProperties = msg.groupProperties
      reRender()
  }

  private def displayTime(label: String, time: DateTime): NodeSeq = {
    val t = time.toString("yyyy-MM-dd HH:mm:ssZ")
    val d = DateFormaterService.getFormatedPeriod(time, DateTime.now)
    // exceptionally not putting {} to remove the node
    <span>{label + t}</span><div class="help-block">{"↳ " + d} ago</div>
  }
  private def displayDate(label: String, time: DateTime): NodeSeq = {
    val t = time.toString("yyyy-MM-dd HH:mm:ssZ")
    <span class="dropdown-header">{label + t}</span>
  }
  private def updateDuration = {
    val content = deploymentStatus.current match {
      case SuccessStatus(_, _, end, _) => displayTime("Ended at ", end)
      case ErrorStatus(_, _, end, _)   => displayTime("Ended at ", end)
      case _                           => NodeSeq.Empty
    }
    SetHtml("deployment-end", content)
  }

  override def render: RenderOut = {
    partialUpdate(updateDuration)
    new RenderOut(layout)
  }

  val deployementErrorMessage: Regex = """(.*)!errormessage!(.*)""".r

  private def statusBackground: String = {
    deploymentStatus.processing match {
      case IdleDeployer =>
        globalStatus match {
          case (_: ErrorStatus, _, _, _)                 =>
            "bg-error"
          case (_, _: CompilationStatusErrors, _, _)     =>
            "bg-error"
          case (_, _, NodeConfigurationStatus.Error, _)  =>
            "bg-error"
          case (_, _, _, GroupConfigurationStatus.Error) =>
            "bg-error"
          case (NoStatus, _, _, _)                       =>
            "bg-neutral"
          case (
                _: SuccessStatus,
                CompilationStatusAllSuccess,
                NodeConfigurationStatus.Success,
                GroupConfigurationStatus.Success
              ) =>
            "bg-ok"
        }
      case _            =>
        "bg-refresh"
    }
  }

  private def lastStatus = {
    def commonStatement(
        start:        DateTime,
        end:          DateTime,
        durationText: String,
        headText:     String,
        iconClass:    String,
        statusClass:  String
    ) = {
      <li><a href="#" class={statusClass + " no-click"}><span class={iconClass}></span>{headText}</a></li>
      <li><a href="#" class="no-click"><span class={statusClass + " fa fa-hourglass-start"}></span>{
        displayTime("Started at ", start)
      }</a>
      </li>
      <li><a href="#" class="no-click"><span class={statusClass + " fa fa-hourglass-end"}></span><span id="deployment-end">{
        displayTime("Ended at ", end)
      }</span></a></li>
      <li><a href="#" class="no-click"><span class={statusClass + " fa fa-clock-o"}></span>{durationText} {
        DateFormaterService.getFormatedPeriod(start, end)
      }</a></li>
    }
    def loadingStatement(start: DateTime) = {
      <li class="dropdown-header">Policies building...</li>
      <li>{displayDate("Started at ", start)}</li>
    }
    deploymentStatus.processing match {
      case IdleDeployer                                        =>
        deploymentStatus.current match {
          case NoStatus                                          => <li class="dropdown-header">Policy update status unavailable</li>
          case SuccessStatus(id, start, end, configurationNodes) =>
            commonStatement(start, end, "Update took", "Policies updated", "text-success fa fa-check", "text-success")
          case ErrorStatus(id, start, end, failure)              =>
            val popupContent = {
              failure.messageChain match {
                case deployementErrorMessage(chain, error) =>
                  <div class="alert alert-danger" role="alert">
                  {chain.split("<-").map(x => { <b>⇨&nbsp;</b> } ++ Text(x) ++ { <br/> })}
                </div>
                <br/>
                <div class="panel-group" role="tablist">
                  <div class="panel panel-default">
                    <a class="" id="showTechnicalErrorDetails" role="button" data-bs-toggle="collapse" href="#collapseListGroup1" aria-expanded="true" aria-controls="collapseListGroup1" onclick="reverseErrorDetails()">
                      <div class="panel-heading" role="tab" id="collapseListGroupHeading1">
                        <h4 class="panel-title">
                          Show technical details
                          <span id="showhidetechnicalerrors" class="fa fa-chevron-up up"></span>
                        </h4>
                      </div>
                    </a>
                    <div id="collapseListGroup1" class="panel-collapse collapse in" role="tabpanel" aria-labelledby="collapseListGroupHeading1" aria-expanded="true">
                      <ul id="deploymentErrorMsg" class="list-group">
                        {error.split("<-").map(x => <li class="list-group-item">{"⇨ " + x}</li>)}
                      </ul>
                    </div>
                  </div>
                </div>

                case _ =>
                  <pre class="code">{
                    failure.messageChain
                      .split("<- ")
                      .map(x => Text("⇨ " + x.replace("cause was:", "\n    cause was:")) ++ { <br/> })
                  }</pre>
              }
            }

            val callback =
              JsRaw("initBsModal('errorDetailsDialog');") & SetHtml("errorDetailsMessage", popupContent) // JsRaw ok, const

            commonStatement(
              start,
              end,
              "Error occurred in",
              "Error during policy update",
              "text-danger fa fa-times",
              "text-danger"
            ) ++
            <li class="footer">{SHtml.a(Text("Details"), callback, ("href", "#"), ("style", "color:#DA291C !important;"))}</li>
        }
      case Processing(id, start)                               => loadingStatement(start)
      case ProcessingAndPendingAuto(asked, current, a, e)      => loadingStatement(current.started)
      case ProcessingAndPendingManual(asked, current, a, e, r) => loadingStatement(current.started)
    }
  }

  private def closePopup(): JsCmd = {
    JsRaw("""hideBsModal('generatePoliciesDialog')""") // JsRaw ok, const
  }

  private def fullPolicyGeneration: NodeSeq = {
    if (havePerm(AuthorizationType.Deployment.Write)) {
      SHtml.ajaxButton(
        "Regenerate",
        () => {
          clearCacheService.clearNodeConfigurationCache(storeEvent = true, CurrentUser.actor) match {
            case Full(_) => // ok
            case eb: EmptyBox =>
              val err = eb ?~! "Error when trying to start policy generation"
              logger.warn(err.messageChain)
          }
          closePopup()
        },
        ("class", "btn btn-danger")
      )
    } else NodeSeq.Empty
  }

  private def showGeneratePoliciesPopup: NodeSeq = {
    val callback = JsRaw("initBsModal('generatePoliciesDialog')") // JsRaw ok, const
    if (havePerm(AuthorizationType.Deployer.Write)) {
      SHtml.a(
        Text("Regenerate all policies"),
        callback,
        ("class", "regeneratePolicies")
      )
    } else NodeSeq.Empty
  }

  private def showGroups:      NodeSeq = {
    def tooltipContent(error: String):       String  = {
      s"<h4 class='tags-tooltip-title'>Properties error</h4><div class='tooltip-inner-content'><pre class=\"code\">${StringEscapeUtils
          .escapeHtml4(error)}</pre></div>"
    }
    def groupBtnId(group: NodeGroupId):      String  = {
      StringEscapeUtils.escapeHtml4(s"status-group-${group.serialize}")
    }
    def groupLinkScript(group: NodeGroupId): NodeSeq = {
      val link  = linkUtil.groupLink(group)
      val btnId = groupBtnId(group)
      scriptLinkButton(btnId, link)
    }

    val failures = groupProperties.collect { case (id, f: FailedNodePropertyHierarchy) => id -> f }
    val groupsErrors: Map[NodeGroup, String] = failures.toList match {
      case Nil      => Map.empty
      case statuses =>
        val groups = nodeGroupRepo.getAllByIds(statuses.map { case (id, _) => id }).runNow
        groups.flatMap(g => failures.get(g.id).map(f => g -> f.getMessage)).toMap
    }
    groupsErrors.toList match {
      case Nil    => NodeSeq.Empty
      case errors =>
        <li class="card border-start-0 border-end-0 border-top-0">
          <div class="card-body">
            <h5 class="card-title d-flex justify-content-between">
              Group configuration errors
              <span class="badge bg-danger">{errors.size}</span>
            </h5>
            <ul class="list-group">
            {
          NodeSeq.fromSeq(
            errors.map {
              case (g, err) =>
                <li class="list-group-item d-flex justify-content-between align-items-center">
                  <button id={groupBtnId(g.id)} class="btn btn-link text-start">
                    {StringEscapeUtils.escapeHtml4(g.name)} <i class="fa fa-external-link"/>
                  </button>
                  {groupLinkScript(g.id)}
                  <span>
                    <i class="fa fa-question-circle info" data-bs-toggle="tooltip" data-bs-placement="top" data-bs-html="true" data-bs-original-title={
                  tooltipContent(err)
                }></i>
                  </span>
                </li>
            }.toList ++ WithNonce.scriptWithNonce(
              Script(OnLoad(JsRaw("""initBsTooltips();""")))
            )
          )
        }
            </ul>
          </div>
        </li>
    }
  }
  private def showNodes:       NodeSeq = {
    def tooltipContent(error: String): String                                = {
      s"<h4 class='tags-tooltip-title'>Properties error</h4><div class='tooltip-inner-content'><pre class=\"code\">${StringEscapeUtils
          .escapeHtml4(error)}</pre></div>"
    }
    def nodeBtnId(node: NodeId):       String                                = {
      s"status-node-${StringEscapeUtils.escapeHtml4(node.value)}"
    }
    // The "a" tag has special display in the content, we need a button that opens link in new tab
    def nodeLinkScript(node: NodeId):  NodeSeq                               = {
      val link  = linkUtil.nodeLink(node)
      val btnId = nodeBtnId(node)
      scriptLinkButton(btnId, link)
    }
    val allNodes = nodeFactRepo.getAll()(QueryContext.systemQC).runNow
    val nodesErrors:                   Map[MinimalNodeFactInterface, String] = nodeProperties.flatMap {
      case (_, _: SuccessNodePropertyHierarchy)     => None
      case (nodeId, f: FailedNodePropertyHierarchy) => allNodes.get(nodeId).map(_ -> f.getMessage)
    }
    nodesErrors.toList match {
      case Nil    => NodeSeq.Empty
      case errors =>
        <li class="card border-start-0 border-end-0 border-top-0">
          <div class="card-body">
            <h5 class="card-title d-flex justify-content-between">
              Node configuration errors
              <span class="badge bg-danger">{errors.size}</span>
            </h5>
            <ul class="list-group">
            {
          NodeSeq.fromSeq(
            errors.map {
              case (n, err) =>
                <li class="list-group-item d-flex justify-content-between align-items-center">
                  <button id={nodeBtnId(n.id)} class="btn btn-link text-start">
                    {StringEscapeUtils.escapeHtml4(n.fqdn)} <i class="fa fa-external-link"/>
                  </button>
                  {nodeLinkScript(n.id)}
                  <span>
                    <i class="fa fa-question-circle info" data-bs-toggle="tooltip" data-bs-placement="top" data-bs-html="true" data-bs-original-title={
                  tooltipContent(err)
                }></i>
                  </span>
                </li>
            }.toList ++ WithNonce.scriptWithNonce(
              Script(OnLoad(JsRaw("""initBsTooltips();""")))
            )
          )
        }
            </ul>
          </div>
        </li>
    }
  }
  private def showCompilation: NodeSeq = {
    def tooltipContent(error: EditorTechniqueError):      String  = {
      s"<h4 class='tags-tooltip-title'>Technique compilation output in ${StringEscapeUtils.escapeHtml4(error.id.value)}/${StringEscapeUtils
          .escapeHtml4(error.version.value)}</h4><div class='tooltip-inner-content'><pre class=\"code\">${error.errorMessage}</pre></div>"
    }
    def techniqueBtnId(error: EditorTechniqueError):      String  = {
      s"status-compilation-${StringEscapeUtils.escapeHtml4(error.id.value)}-${StringEscapeUtils.escapeHtml4(error.version.value)}"
    }
    // The "a" tag has special display in the content, we need a button that opens link in new tab
    def techniqueLinkScript(error: EditorTechniqueError): NodeSeq = {
      val link  = linkUtil.techniqueLink(error.id.value)
      val btnId = techniqueBtnId(error)
      scriptLinkButton(btnId, link)
    }
    compilationStatus match {
      case CompilationStatusAllSuccess                =>
        NodeSeq.Empty
      case CompilationStatusErrors(techniquesInError) =>
        <li class="card border-start-0 border-end-0 border-top-0">
          <div class="card-body">
            <h5 class="card-title d-flex">
              Technique compilation errors 
              <span class="badge bg-danger float h-25">{techniquesInError.size}</span>
            </h5>
            <ul class="list-group">
            {
          NodeSeq.fromSeq(
            techniquesInError
              .map(t => {
                <li class="list-group-item d-flex justify-content-between align-items-center">
                  <button id={techniqueBtnId(t)} class="btn btn-link text-start">
                    {StringEscapeUtils.escapeHtml4(t.name)} <i class="fa fa-external-link"/>
                  </button>
                  {techniqueLinkScript(t)}
                  <span>
                    <i class="fa fa-question-circle info" data-bs-toggle="tooltip" data-bs-placement="top" data-bs-html="true" data-bs-original-title={
                  tooltipContent(t)
                }></i>
                  </span>
                </li>
              })
              .toList ++ WithNonce.scriptWithNonce(
              Script(OnLoad(JsRaw("""initBsTooltips();""")))
            )
          )
        }
            </ul>
          </div>
        </li>
    }
  }

  private def layout = {
    if (havePerm(AuthorizationType.Deployment.Read)) {

      <li class={"nav-item dropdown notifications-menu " ++ statusBackground}>
        <a href="#" class="dropdown-toggle" role="button" data-bs-toggle="dropdown" aria-expanded="false">
          Status
          <i class="fa fa-heartbeat"></i>
          <span id="generation-status" class="label"><span></span></span>
        </a>
        <ul class="dropdown-menu">
          {lastStatus}
          <li class="footer">
            {showGeneratePoliciesPopup}
          </li>
          {showGroups}
          {showNodes}
          {showCompilation}
        </ul>
      </li> ++ errorPopup ++ generatePoliciesPopup

    } else NodeSeq.Empty
  }

  private def errorPopup = {
    <div class="modal fade" tabindex="-1" id="errorDetailsDialog" data-bs-backdrop="false">
        <div class="modal-dialog modal-lg">
            <div class="modal-content">
                <div class="modal-header">
                    <h5 class="modal-title">Error</h5>
                    <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
                </div>
                <div class="modal-body">
                    <div class="row space-bottom">
                        <div class="col-xl-12">
                            <h4 class="text-center">
                                Policy update process was stopped due to an error:
                            </h4>
                        </div>
                    </div>
                    <div id="errorDetailsMessage" class="space-bottom space-top">
                        [Here come the error message]
                    </div>
                </div>
                <div class="modal-footer">
                    <button type="button" class="btn btn-default" data-bs-dismiss="modal">Close</button>
                </div>
            </div>
        </div>
      </div>
  }

  private def generatePoliciesPopup = {
    <div class="modal fade" tabindex="-1" id="generatePoliciesDialog" aria-hidden="true" data-bs-backdrop="false" data-bs-dismiss="modal">
      <div class="modal-dialog">
        <div class="modal-content">
          <div class="modal-header">
            <h5 class="modal-title">Regenerate Policies</h5>
            <button type="button" class="btn-close" data-bs-dismiss="modal" aria-label="Close"></button>
          </div>
          <div class="modal-body">
            <div class="row space-bottom">
              <div class="col-xl-12">
                <h4 class="text-center">
                  Are you sure that you want to regenerate all policies ?
                </h4>
              </div>
            </div>
          </div>
          <div class="modal-footer">
            <button type="button" class="btn btn-default" data-bs-dismiss="modal">Close</button>
            {fullPolicyGeneration}
          </div>
        </div>
      </div>
    </div>
  }

  private def scriptLinkButton(btnId: String, link: String): Node = {
    WithNonce.scriptWithNonce(
      Script(
        OnLoad(JsRaw(s"""
            document.getElementById("${StringEscapeUtils.escapeEcmaScript(btnId)}").onclick = function () {
              window.open("${StringEscapeUtils.escapeEcmaScript(link)}", "_blank");
            };
            """))
      )
    )
  }
}

private object AsyncDeployment {
  sealed trait NodeConfigurationStatus

  object NodeConfigurationStatus {

    case object Error   extends NodeConfigurationStatus
    case object Success extends NodeConfigurationStatus

    def fromProperties(properties: Map[NodeId, ResolvedNodePropertyHierarchy]): NodeConfigurationStatus = {
      val hasError = properties.collectFirst { case (_, _: FailedNodePropertyHierarchy) => () }.isDefined
      if (hasError) Error
      else Success
    }
  }

  sealed trait GroupConfigurationStatus

  object GroupConfigurationStatus {

    case object Error   extends GroupConfigurationStatus
    case object Success extends GroupConfigurationStatus

    def fromProperties(properties: Map[NodeGroupId, ResolvedNodePropertyHierarchy]): GroupConfigurationStatus = {
      val hasError = properties.collectFirst { case (_, _: FailedNodePropertyHierarchy) => () }.isDefined
      if (hasError) Error
      else Success
    }
  }
}
