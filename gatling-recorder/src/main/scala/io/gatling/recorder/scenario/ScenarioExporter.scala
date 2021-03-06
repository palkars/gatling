/**
 * Copyright 2011-2014 eBusiness Information, Groupe Excilys (www.ebusinessinformation.fr)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * 		http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.gatling.recorder.scenario

import java.io.{ FileOutputStream, IOException }

import scala.annotation.tailrec
import scala.collection.immutable.SortedMap
import scala.reflect.io.Path.string2path
import scala.tools.nsc.io.{ Directory, File }

import com.typesafe.scalalogging.slf4j.StrictLogging

import io.gatling.core.util.IOHelper.withCloseable
import io.gatling.http.HeaderNames
import io.gatling.recorder.config.RecorderConfiguration.configuration
import io.gatling.recorder.scenario.template.SimulationTemplate

object ScenarioExporter extends StrictLogging {

	private val EVENTS_GROUPING = 100

	def getSimulationFileName: String = s"${configuration.core.className}.scala"

	def getOutputFolder = {
		val path = configuration.core.outputFolder + File.separator + configuration.core.pkg.replace(".", File.separator)
		getFolder(path)
	}

	def saveScenario(scenarioElements: Scenario): Unit = {
		require(!scenarioElements.isEmpty)

		val output = renderScenarioAndDumpBodies(scenarioElements)

		withCloseable(new FileOutputStream(File(getOutputFolder / getSimulationFileName).jfile)) {
			_.write(output.getBytes(configuration.core.encoding))
		}
	}

	private def renderScenarioAndDumpBodies(scenario: Scenario): String = {
		// Aggregate headers
		val filteredHeaders = Set(HeaderNames.COOKIE, HeaderNames.CONTENT_LENGTH, HeaderNames.HOST) ++
			(if (configuration.http.automaticReferer) Set(HeaderNames.REFERER) else Set.empty)

		val scenarioElements = scenario.elements
		val baseUrl = getBaseUrl(scenarioElements)
		val baseHeaders = getBaseHeaders(scenarioElements)
		val protocolConfigElement = new ProtocolElement(baseUrl, baseHeaders)

		// extract the request elements and set all the necessary
		val elements = scenarioElements.map {
			case reqEl: RequestElement => reqEl.makeRelativeTo(baseUrl)
			case el => el
		}

		val requestElements: Seq[RequestElement] = elements.collect { case reqEl: RequestElement => reqEl }
			.zipWithIndex.map { case (reqEl, index) => reqEl.setId(index) }

		// dump request body if needed
		requestElements.foreach(el => el.body.foreach {
			case RequestBodyBytes(bytes) => dumpRequestBody(el.id, bytes, configuration.core.className)
			case _ =>
		})

		val headers: Map[Int, Seq[(String, String)]] = {

			@tailrec
			def generateHeaders(elements: Seq[RequestElement], headers: Map[Int, List[(String, String)]]): Map[Int, List[(String, String)]] = elements match {
				case Seq() => headers
				case element +: others => {
					val acceptedHeaders = element.headers.toList
						.filterNot {
							case (headerName, headerValue) => filteredHeaders.contains(headerName) || baseHeaders.get(headerName).exists(_ == headerValue)
						}
						.sortBy(_._1)

					val newHeaders = if (acceptedHeaders.isEmpty) {
						element.filteredHeadersId = None
						headers

					} else {
						val headersSeq = headers.toSeq
						headersSeq.indexWhere {
							case (id, existingHeaders) => existingHeaders == acceptedHeaders
						} match {
							case -1 =>
								element.filteredHeadersId = Some(element.id)
								headers + (element.id -> acceptedHeaders)
							case index =>
								element.filteredHeadersId = Some(headersSeq(index)._1)
								headers
						}
					}

					generateHeaders(others, newHeaders)
				}
			}

			SortedMap(generateHeaders(requestElements, Map.empty).toSeq: _*)
		}

		val newScenarioElements = getChains(elements)

		SimulationTemplate.render(configuration.core.pkg, configuration.core.className, protocolConfigElement, headers, "Scenario Name", newScenarioElements)
	}

	private def getBaseHeaders(scenarioElements: Seq[ScenarioElement]): Map[String, String] = {
		def addHeader(appendTo: Map[String, String], headerName: String): Map[String, String] =
			getMostFrequentHeaderValue(scenarioElements, headerName)
				.map(headerValue => appendTo + (headerName -> headerValue))
				.getOrElse(appendTo)

		@tailrec
		def resolveBaseHeaders(headers: Map[String, String], headerNames: List[String]): Map[String, String] = headerNames match {
			case Nil => headers
			case headerName :: others => resolveBaseHeaders(addHeader(headers, headerName), others)
		}

		resolveBaseHeaders(Map.empty, ProtocolElement.baseHeaders.keySet.toList)
	}

	private def getBaseUrl(scenarioElements: Seq[ScenarioElement]): String = {
		val urlsOccurrences = scenarioElements.collect {
			case reqElm: RequestElement => reqElm.baseUrl
		}.groupBy(identity).mapValues(_.size).toSeq

		urlsOccurrences.maxBy(_._2)._1
	}

	private def getMostFrequentHeaderValue(scenarioElements: Seq[ScenarioElement], headerName: String): Option[String] = {
		val headers = scenarioElements.flatMap {
			case reqElm: RequestElement => reqElm.headers.collect { case (name, value) if name == headerName => value }
			case _ => Nil
		}

		if (headers.isEmpty) None
		else {
			val headersValuesOccurrences = headers.groupBy(identity).mapValues(_.size).toSeq
			val mostFrequentValue = headersValuesOccurrences.maxBy(_._2)._1
			Some(mostFrequentValue)
		}
	}

	private def getChains(scenarioElements: Seq[ScenarioElement]): Either[Seq[ScenarioElement], List[Seq[ScenarioElement]]] = {

		if (scenarioElements.size > ScenarioExporter.EVENTS_GROUPING)
			Right(scenarioElements.grouped(ScenarioExporter.EVENTS_GROUPING).toList)
		else
			Left(scenarioElements)
	}

	private def dumpRequestBody(idEvent: Int, content: Array[Byte], simulationClass: String) {
		val fileName = s"${simulationClass}_request_$idEvent.txt"
		withCloseable(File(getFolder(configuration.core.requestBodiesFolder) / fileName).outputStream()) {
			fw =>
				try {
					fw.write(content)
				} catch {
					case e: IOException => logger.error("Error, while dumping request body...", e)
				}
		}
	}

	private def getFolder(folderPath: String) = Directory(folderPath).createDirectory()
}