/*
 * Copyright 2018-2023 EMBL - European Bioinformatics Institute
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License. You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package uk.ac.ebi.ena.webin.cli.service;

import java.io.StringReader;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import uk.ac.ebi.ena.webin.cli.service.exception.ServiceException;
import uk.ac.ebi.ena.webin.cli.service.exception.ServiceMessage;
import uk.ac.ebi.ena.webin.cli.utils.RetryUtils;
import uk.ac.ebi.ena.webin.cli.validator.reference.Attribute;
import uk.ac.ebi.ena.webin.cli.validator.reference.Sample;

public class SampleXmlService extends WebinService {
  private static final Logger LOGGER = LoggerFactory.getLogger(SampleXmlService.class);

  public static final String SERVICE_NAME = "SampleXml";

  public static class Builder extends AbstractBuilder<SampleXmlService> {
    @Override
    public SampleXmlService build() {
      return new SampleXmlService(this);
    }
  }
  ;

  public SampleXmlService(AbstractBuilder<SampleXmlService> builder) {
    super(builder);
  }

  public Sample getSample(String sampleId) {
    RestTemplate restTemplate = new RestTemplate();
    HttpHeaders headers = getAuthHeader();
    ResponseEntity<String> response = executeHttpGet(restTemplate, headers, sampleId);
    if (response == null) {
      throw new ServiceException(ServiceMessage.SAMPLE_SERVICE_VALIDATION_ERROR.format(sampleId));
    }
    return getSampleFromXml(sampleId, response.getBody());
  }

  private Sample getSampleFromXml(String sampleId, String sampleXml) {

    try {
      Sample sample = new Sample();

      DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
      DocumentBuilder db = dbf.newDocumentBuilder();
      Document doc = db.parse(new InputSource(new StringReader(sampleXml)));
      doc.getDocumentElement().normalize();

      // name
      NodeList sampleList = doc.getElementsByTagName("SAMPLE");
      for (int i = 0; i < Math.min(1, sampleList.getLength()); i++) {
        Node node = sampleList.item(i);
        if (node.getNodeType() == Node.ELEMENT_NODE) {
          Element element = (Element) node;
          sample.setName(element.getAttribute("alias"));
        }
      }

      // taxid, organism
      NodeList sampleNameList = doc.getElementsByTagName("SAMPLE_NAME");
      for (int i = 0; i < Math.min(1, sampleNameList.getLength()); i++) {
        Node node = sampleNameList.item(i);
        if (node.getNodeType() == Node.ELEMENT_NODE) {
          Element element = (Element) node;
          if (element.getElementsByTagName("TAXON_ID").getLength() > 0) {
            sample.setTaxId(
                Integer.valueOf(element.getElementsByTagName("TAXON_ID").item(0).getTextContent()));
          }
          if (element.getElementsByTagName("SCIENTIFIC_NAME").getLength() > 0) {
            sample.setOrganism(
                element.getElementsByTagName("SCIENTIFIC_NAME").item(0).getTextContent());
          }
        }
      }

      // attributes
      NodeList attributeList = doc.getElementsByTagName("SAMPLE_ATTRIBUTE");
      for (int i = 0; i < attributeList.getLength(); i++) {
        Node node = attributeList.item(i);
        if (node.getNodeType() == Node.ELEMENT_NODE) {
          Element element = (Element) node;
          String tag = element.getElementsByTagName("TAG").item(0).getTextContent();
          String value =
              element.getElementsByTagName("VALUE").getLength() > 0
                  ? element.getElementsByTagName("VALUE").item(0).getTextContent()
                  : null;
          String units =
              element.getElementsByTagName("UNITS").getLength() > 0
                  ? element.getElementsByTagName("UNITS").item(0).getTextContent()
                  : null;
          sample.addAttribute(new Attribute(tag, value, null, units, null));
        }
      }
      return sample;
    } catch (Exception ex) {
      throw new ServiceException(
          ex, ServiceMessage.SAMPLE_SERVICE_VALIDATION_ERROR.format(sampleId));
    }
  }

  private ResponseEntity<String> executeHttpGet(
      RestTemplate restTemplate, HttpHeaders headers, String sampleId) {

    return RetryUtils.executeWithRetry(
        context ->
            restTemplate.exchange(
                resolveAgainstWebinRestV1Uri("samples/{id}"),
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class,
                sampleId.trim()),
        context -> LOGGER.warn("Retrying sample xml retrieval from server."),
        HttpServerErrorException.class,
        ResourceAccessException.class);
  }
}
