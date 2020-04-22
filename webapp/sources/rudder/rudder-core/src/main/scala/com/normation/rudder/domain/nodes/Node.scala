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

package com.normation.rudder.domain.nodes

import com.normation.errors.Inconsistency
import com.normation.errors.PureResult
import com.normation.inventory.domain.FullInventory
import com.normation.inventory.domain.KeyStatus
import com.normation.inventory.domain.NodeId
import com.normation.inventory.domain.PublicKey
import com.normation.inventory.domain.SecurityToken
import com.normation.rudder.domain.policies.PolicyMode
import com.normation.rudder.domain.policies.SimpleDiff
import com.normation.rudder.reports.AgentRunInterval
import com.normation.rudder.reports.HeartbeatConfiguration
import com.normation.rudder.reports.ReportingConfiguration
import com.normation.rudder.repository.json.DataExtractor.CompleteJson
import com.normation.rudder.repository.json.DataExtractor.OptionnalJson
import com.normation.rudder.services.policies.ParameterEntry
import net.liftweb.common.Box
import net.liftweb.common.Failure
import net.liftweb.common.Full
import net.liftweb.http.S
import net.liftweb.json.JsonAST._
import net.liftweb.json.JsonParser.ParseException
import org.joda.time.DateTime

/**
 * The entry point for a REGISTERED node in Rudder.
 *
 * This is independant from inventory, and can exist without one.
 *
 */
final case class Node(
    id                        : NodeId
  , name                      : String
  , description               : String
  , state                     : NodeState
  , isSystem                  : Boolean
  , isPolicyServer            : Boolean
  , creationDate              : DateTime
  , nodeReportingConfiguration: ReportingConfiguration
  , properties                : List[NodeProperty]
  , policyMode                : Option[PolicyMode]
)

case object Node {
  def apply (inventory : FullInventory) : Node = {
    Node(
        inventory.node.main.id
      , inventory.node.main.hostname
      , inventory.node.description.getOrElse("")
      , NodeState.Enabled
      , false
      , false
      , inventory.node.inventoryDate.getOrElse(new DateTime(0))
      , ReportingConfiguration(None,None, None)
      , Nil
      , None
    )
  }
}

sealed trait NodeState { def name: String }
object NodeState {

  final case object Enabled          extends NodeState { val name = "enabled"       }
  final case object Ignored          extends NodeState { val name = "ignored"       }
  final case object EmptyPolicies    extends NodeState { val name = "empty-policies"}
  final case object Initializing     extends NodeState { val name = "initializing"  }
  final case object PreparingEOL     extends NodeState { val name = "preparing-eol" }

  def values = ca.mrvisser.sealerate.values[NodeState]


  // human readable, sorted list of (state, label)
  def labeledPairs = {
    val a = values.toList
    val b = a.map { x => x match {
      case NodeState.Initializing  => (0, x, S.?("node.states.initializing"))
      case NodeState.Enabled       => (1, x, S.?("node.states.enabled"))
      case NodeState.EmptyPolicies => (2, x, S.?("node.states.empty-policies"))
      case NodeState.Ignored       => (3, x, S.?("node.states.ignored"))
      case NodeState.PreparingEOL  => (4, x, S.?("node.states.preparing-eol"))
    } }

    b.sortBy( _._1 ).map{ case (_, x, label) =>
      (x, label)
    }
  }

}

/*
 * Name of the owner of a node property.
 */
final case class NodePropertyProvider(value: String) extends AnyVal

/**
 * A node property is a key/value pair + metadata.
 * For now, only metadata availables is:
 * - the provider of the property. By default Rudder.
 *
 * Only the provider of a property can modify it.
 */
final case class NodeProperty(
    name    : String
  , value   : JValue
  , provider: Option[NodePropertyProvider] // optional, default "rudder"
) {
  def renderValue: String = value match {
    case JString(s) => s
    case v          => net.liftweb.json.compactRender(v)
  }
}


object GenericPropertyUtils {
  import net.liftweb.json.JsonAST.JNothing
  import net.liftweb.json.JsonAST.JString
  import net.liftweb.json.{parse => jsonParse}

  /**
   * Parse a value that can be a string or some json.
   */
  def parseValue(value: String): JValue = {
    try {
      jsonParse(value) match {
        case JNothing => JString("")
        case json     => json
      }
    } catch {
      case ex: ParseException =>
        // in that case, we didn't had a valid json top-level structure,
        // i.e either object or array. Use a JString with the content
        JString(value)
    }
  }

  /**
   * Write back a value as a string. There is
   * some care to take, because simple jvalue (string, boolean, etc)
   * must be written directly as string without quote.
   */
  def serializeValue(value: JValue): String = {
    value match {
      case JNothing | JNull => ""
      case JString(s)       => s
      case JBool(v)         => v.toString
      case JDouble(v)       => v.toString
      case JInt(v)          => v.toString
      case json             => compactRender(json)
    }
  }
}


object NodeProperty {

  val rudderNodePropertyProvider = NodePropertyProvider("default")

  // the provider that manages inventory custom properties
  val customPropertyProvider = NodePropertyProvider("inventory")

  /**
   * A builder with the logic to handle the value part.
   *
   * For compatibity reason, we want to be able to process
   * empty (JNothing) and primitive types, especially string, specificaly as
   * a JString *but* a string representing an actual JSON should be
   * used as json.
   */
  def apply(name: String, value: String, provider: Option[NodePropertyProvider]): NodeProperty = {
    NodeProperty(name, GenericPropertyUtils.parseValue(value), provider)
  }
}

object CompareProperties {
  import cats.implicits._

  /**
   * Update a set of properties with the map:
   * - if a key of the map matches a property name,
   *   use the map value for the key as value for
   *   the property
   * - if the value is the emtpy string, remove
   *   the property
   *
   * Each time, we have to check the provider of the update to see if it's compatible.
   * Node that in read-write mode, the provider is the last who wrote the property.
   *
   * A "none" provider actually means Rudder system one.
   */
  def updateProperties(oldProps: List[NodeProperty], optNewProps: Option[List[NodeProperty]]): PureResult[List[NodeProperty]] = {

    //when we compare providers, we actually compared them with "none" replaced by RudderProvider
    //if the old provider is None/default, it can always be updated by new
    def canBeUpdated(old: Option[NodePropertyProvider], newer: Option[NodePropertyProvider]) = {
      old match {
        case None | Some(NodeProperty.rudderNodePropertyProvider) =>
          true
        case Some(p1) =>
          p1 == newer.getOrElse(NodeProperty.rudderNodePropertyProvider)
      }
    }
    //check if the prop should be removed or updated
    def updateOrRemoveProp(oldValue:JValue, newProp: NodeProperty): Either[String, NodeProperty] = {
     if(newProp.value == JString("")) {
       Left(newProp.name)
     } else {
       Right(newProp.copy(value = oldValue.merge(newProp.value)))
     }
    }

    optNewProps match {
      case None => Right(oldProps)
      case Some(newProps) =>
        val oldPropsMap = oldProps.map(p => (p.name, p)).toMap

        //update only according to rights - we get a seq of option[either[remove, update]]
        for {
          updated <- newProps.toList.traverse { newProp =>
                       oldPropsMap.get(newProp.name) match {
                         case None =>
                           Right(updateOrRemoveProp(JNothing, newProp))
                         case Some(oldProp@NodeProperty(name, value, provider)) =>
                             if(canBeUpdated(old = provider, newer = newProp.provider)) {
                               Right(updateOrRemoveProp(value, newProp))
                             } else {
                               val old = provider.getOrElse(NodeProperty.rudderNodePropertyProvider).value
                               val current = newProp.provider.getOrElse(NodeProperty.rudderNodePropertyProvider).value
                               Left(Inconsistency(s"You can not update property '${name}' which is owned by provider '${old}' thanks to provider '${current}'"))
                             }
                       }
                     }
        } yield {
          val toRemove = updated.collect { case Left(name)  => name }.toSet
          val toUpdate = updated.collect { case Right(prop) => (prop.name, prop) }.toMap
          // merge properties
          (oldPropsMap.view.filterKeys(k => !toRemove.contains(k)).toMap ++ toUpdate).map(_._2).toList
        }
    }
  }

}

/**
 * Node diff for event logs:
 * Change
 * - heartbeat frequency
 * - run interval
 * - properties
 *
 * For now, other simple properties are not handle.
 */

sealed trait NodeDiff

/**
 * Denote a change on the heartbeat frequency.
 */
object ModifyNodeHeartbeatDiff{
  def apply(id: NodeId,  modHeartbeat: Option[SimpleDiff[Option[HeartbeatConfiguration]]]) = ModifyNodeDiff(id,modHeartbeat, None, None, None, None, None)
}

/**
 * Diff on a change on agent run period
 */
object ModifyNodeAgentRunDiff{
  def apply(id: NodeId, modAgentRun: Option[SimpleDiff[Option[AgentRunInterval]]]) = ModifyNodeDiff(id,None,modAgentRun, None, None, None, None)
}

/**
 * Diff on the list of properties
 */
object ModifyNodePropertiesDiff{
  def apply(id: NodeId, modProperties: Option[SimpleDiff[List[NodeProperty]]]) = ModifyNodeDiff(id,None,None, modProperties, None, None, None)
}

/**
 * Diff on the list of properties
 */
final case class ModifyNodeDiff(
    id           : NodeId
  , modHeartbeat : Option[SimpleDiff[Option[HeartbeatConfiguration]]]
  , modAgentRun  : Option[SimpleDiff[Option[AgentRunInterval]]]
  , modProperties: Option[SimpleDiff[List[NodeProperty]]]
  , modPolicyMode: Option[SimpleDiff[Option[PolicyMode]]]
  , modKeyValue  : Option[SimpleDiff[SecurityToken]]
  , modKeyStatus : Option[SimpleDiff[KeyStatus]]
)

object ModifyNodeDiff {
  def apply(oldNode : Node, newNode : Node) : ModifyNodeDiff = {
    val policy     = if (oldNode.policyMode == newNode.policyMode) None else Some(SimpleDiff(oldNode.policyMode,newNode.policyMode))
    val properties = if (oldNode.properties.toSet == newNode.properties.toSet) None else Some(SimpleDiff(oldNode.properties,newNode.properties))
    val agentRun   = if (oldNode.nodeReportingConfiguration.agentRunInterval == newNode.nodeReportingConfiguration.agentRunInterval) None else Some(SimpleDiff(oldNode.nodeReportingConfiguration.agentRunInterval,newNode.nodeReportingConfiguration.agentRunInterval))
    val heartbeat  = if (oldNode.nodeReportingConfiguration.heartbeatConfiguration == newNode.nodeReportingConfiguration.heartbeatConfiguration) None else Some(SimpleDiff(oldNode.nodeReportingConfiguration.heartbeatConfiguration,newNode.nodeReportingConfiguration.heartbeatConfiguration))

    ModifyNodeDiff(newNode.id, heartbeat, agentRun, properties, policy, None, None)
  }

  def keyInfo(nodeId: NodeId, oldKeys: List[SecurityToken], oldStatus: KeyStatus, key: Option[SecurityToken], status: Option[KeyStatus]): ModifyNodeDiff = {
    val keyInfo = key match {
      case None    => None
      case Some(k) =>
        oldKeys match {
          case Nil    => Some(SimpleDiff(PublicKey(""), k))
          case x :: _ => if(k == x) None else Some(SimpleDiff(x, k))
        }
    }
    val keyStatus = status match {
      case None    => None
      case Some(s) => if(s == oldStatus) None else Some(SimpleDiff(oldStatus, s))
    }

    ModifyNodeDiff(nodeId, None, None, None, None, keyInfo, keyStatus)
  }
}

/**
 * The part dealing with JsonSerialisation of node related
 * attributes (especially properties) and parameters
 */
object JsonSerialisation {

  import net.liftweb.json.JsonDSL._
  import net.liftweb.json._

  implicit class JsonNodeProperty(val x: NodeProperty) extends AnyVal {
    def toJson(): JObject = (
        ( "name"     -> x.name  )
      ~ ( "value"    -> x.value )
      ~ ( "provider" -> x.provider.map(_.value) )
    )
  }

  implicit class JsonNodeProperties(val props: Seq[NodeProperty]) extends AnyVal {
    implicit def formats = DefaultFormats

    def dataJson(x: NodeProperty) : JField = {
      JField(x.name, x.value)
    }

    def toApiJson(): JArray = {
      JArray(props.map(_.toJson()).toList)
    }

    def toDataJson(): JObject = {
      props.map(dataJson(_)).toList.sortBy { _.name }
    }
  }

  implicit class JsonParameter(val x: ParameterEntry) extends AnyVal {
    def toJson(): JObject = (
        ( "name"     -> x.parameterName )
      ~ ( "value"    -> x.escapedValue  )
    )
  }

  implicit class JsonParameters(val parameters: Set[ParameterEntry]) extends AnyVal {
    implicit def formats = DefaultFormats

    def dataJson(x: ParameterEntry) : JField = {
      JField(x.parameterName, x.escapedValue)
    }

    def toDataJson(): JObject = {
      parameters.map(dataJson(_)).toList.sortBy { _.name }
    }
  }


  def unserializeLdapNodeProperty(json:JValue): Box[NodeProperty] = {

    for {
       name  <- CompleteJson.extractJsonString(json, "name")
       value <- json \ "value" match { case JNothing => Failure("Cannot be Empty")
                                       case value => Full(value)
                                     }
       provider <- OptionnalJson.extractJsonString(json, "provider", p => Full(NodePropertyProvider(p)) )
    } yield {
      NodeProperty(name,value,provider)
    }
  }

}
