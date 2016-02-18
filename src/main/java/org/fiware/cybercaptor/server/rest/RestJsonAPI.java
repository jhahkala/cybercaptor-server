/****************************************************************************************
 * This file is part of FIWARE CyberCAPTOR,                                             *
 * instance of FIWARE Cyber Security Generic Enabler                                    *
 * Copyright (C) 2012-2015  Thales Services S.A.S.,                                     *
 * 20-22 rue Grande Dame Rose 78140 VELIZY-VILACOUBLAY FRANCE                           *
 *                                                                                      *
 * FIWARE CyberCAPTOR is free software; you can redistribute                            *
 * it and/or modify it under the terms of the GNU General Public License                *
 * as published by the Free Software Foundation; either version 3 of the License,       *
 * or (at your option) any later version.                                               *
 *                                                                                      *
 * FIWARE CyberCAPTOR is distributed in the hope                                        *
 * that it will be useful, but WITHOUT ANY WARRANTY; without even the implied           *
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the            *
 * GNU General Public License for more details.                                         *
 *                                                                                      *
 * You should have received a copy of the GNU General Public License                    *
 * along with FIWARE CyberCAPTOR.                                                       *
 * If not, see <http://www.gnu.org/licenses/>.                                          *
 ****************************************************************************************/
package org.fiware.cybercaptor.server.rest;

import org.apache.commons.io.IOUtils;
import org.fiware.cybercaptor.server.api.AttackPathManagement;
import org.fiware.cybercaptor.server.api.IDMEFManagement;
import org.fiware.cybercaptor.server.api.InformationSystemManagement;
import org.fiware.cybercaptor.server.attackgraph.AttackGraph;
import org.fiware.cybercaptor.server.attackgraph.AttackPath;
import org.fiware.cybercaptor.server.attackgraph.MulvalAttackGraph;
import org.fiware.cybercaptor.server.attackgraph.Vertex;
import org.fiware.cybercaptor.server.database.Database;
import org.fiware.cybercaptor.server.informationsystem.InformationSystem;
import org.fiware.cybercaptor.server.monitoring.Monitoring;
import org.fiware.cybercaptor.server.properties.ProjectProperties;
import org.fiware.cybercaptor.server.remediation.DeployableRemediation;
import org.glassfish.jersey.media.multipart.FormDataBodyPart;
import org.glassfish.jersey.media.multipart.FormDataContentDisposition;
import org.glassfish.jersey.media.multipart.FormDataParam;
import org.jdom2.Element;
import org.jdom2.output.Format;
import org.jdom2.output.XMLOutputter;
import org.json.JSONObject;
import org.json.XML;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JSON Rest API, main API, since the XML API has been depreciated.
 *
 * @author Francois -Xavier Aguessy
 */
@Path("/json/")
public class RestJsonAPI {

    /**
     * Generates the attack graph and initializes the main objects for other API calls
     * (database, attack graph, attack paths,...)
     *
     * @param request the HTTP request
     * @return the HTTP response
     * @throws Exception
     */
    @GET
    @Path("initialize")
    @Produces(MediaType.APPLICATION_JSON)
    public Response initialise(@Context HttpServletRequest request) throws Exception {
        String costParametersFolderPath = ProjectProperties.getProperty("cost-parameters-path");
        String databasePath = ProjectProperties.getProperty("database-path");

        //Load the vulnerability and remediation database
        Database database = new Database(databasePath);

        String topologyFilePath = ProjectProperties.getProperty("topology-path");

        Logger.getAnonymousLogger().log(Level.INFO, "Generating topology and mulval inputs " + topologyFilePath);
        InformationSystemManagement.prepareMulVALInputs();

        Logger.getAnonymousLogger().log(Level.INFO, "Loading topology " + topologyFilePath);
        InformationSystem informationSystem = InformationSystemManagement.loadTopologyXMLFile(topologyFilePath, database);


        AttackGraph attackGraph = InformationSystemManagement.generateAttackGraphWithMulValUsingAlreadyGeneratedMulVALInputFile();
        if (attackGraph == null)
            return RestApplication.returnErrorMessage(request, "the attack graph is empty");
        Logger.getAnonymousLogger().log(Level.INFO, "Launch scoring function");
        attackGraph.loadMetricsFromTopology(informationSystem);
        List<AttackPath> attackPaths = AttackPathManagement.scoreAttackPaths(attackGraph, attackGraph.getNumberOfVertices());

        //Delete attack paths that have less than 3 hosts (attacker that pown its own host).
        List<AttackPath> attackPathToKeep = new ArrayList<AttackPath>();
        for (AttackPath attackPath : attackPaths) {
            if (attackPath.vertices.size() > 3) {
                attackPathToKeep.add(attackPath);
            }
        }
        attackPaths = attackPathToKeep;

        Logger.getAnonymousLogger().log(Level.INFO, attackPaths.size() + " attack paths scored");
        Monitoring monitoring = new Monitoring(costParametersFolderPath);
        monitoring.setAttackPathList(attackPaths);
        monitoring.setInformationSystem(informationSystem);
        monitoring.setAttackGraph((MulvalAttackGraph) attackGraph);

        request.getSession(true).setAttribute("database", database);
        request.getSession(true).setAttribute("monitoring", monitoring);

        return RestApplication.returnJsonObject(request, new JSONObject().put("status", "Loaded"));
    }

    /**
     * OPTIONS call necessary for the Access-Control-Allow-Origin of the POST
     *
     * @return the HTTP response
     */
    @OPTIONS
    @Path("/initialize")
    public Response initializeOptions(@Context HttpServletRequest request) {
        return RestApplication.returnJsonObject(request, new JSONObject());
    }

    /**
     * Generates the attack graph and initializes the main objects for other API calls
     * (database, attack graph, attack paths,...).
     * Load the objects from the POST XML file describing the whole network topology
     *
     * @param request the HTTP request
     * @return the HTTP response
     * @throws Exception
     */
    @POST
    @Path("/initialize")
    @Consumes(MediaType.APPLICATION_XML)
    @Produces(MediaType.APPLICATION_JSON)
    public Response initializeFromXMLText(@Context HttpServletRequest request, String xmlString) throws Exception {
        String costParametersFolderPath = ProjectProperties.getProperty("cost-parameters-path");
        String databasePath = ProjectProperties.getProperty("database-path");

        if (xmlString == null || xmlString.isEmpty())
            return RestApplication.returnErrorMessage(request, "The input text string is empty.");

        Logger.getAnonymousLogger().log(Level.INFO, "Load the vulnerability and remediation database");
        Database database = new Database(databasePath);

        String topologyFilePath = ProjectProperties.getProperty("topology-path");

        Logger.getAnonymousLogger().log(Level.INFO, "Storing topology in " + topologyFilePath);
        PrintWriter out = new PrintWriter(topologyFilePath);
        out.print(xmlString);
        out.close();

        Logger.getAnonymousLogger().log(Level.INFO, "Loading topology " + topologyFilePath);

        InformationSystem informationSystem = InformationSystemManagement.loadTopologyXMLFile(topologyFilePath, database);

        AttackGraph attackGraph = InformationSystemManagement.prepareInputsAndExecuteMulVal(informationSystem);

        if (attackGraph == null){
            Logger.getAnonymousLogger().log(Level.INFO, "the attack graph is empty");

            return RestApplication.returnErrorMessage(request, "the attack graph is empty");
        }
        Logger.getAnonymousLogger().log(Level.INFO, "Launch scoring function");
        attackGraph.loadMetricsFromTopology(informationSystem);

        List<AttackPath> attackPaths = AttackPathManagement.scoreAttackPaths(attackGraph, attackGraph.getNumberOfVertices());

        //Delete attack paths that have less than 3 hosts (attacker that pown its own host).
        List<AttackPath> attackPathToKeep = new ArrayList<AttackPath>();
        for (AttackPath attackPath : attackPaths) {
            if (attackPath.vertices.size() > 3) {
                attackPathToKeep.add(attackPath);
            }
        }
        attackPaths = attackPathToKeep;

        Logger.getAnonymousLogger().log(Level.INFO, attackPaths.size() + " attack paths scored");
        Monitoring monitoring = new Monitoring(costParametersFolderPath);
        monitoring.setAttackPathList(attackPaths);
        monitoring.setInformationSystem(informationSystem);
        monitoring.setAttackGraph((MulvalAttackGraph) attackGraph);

        request.getSession(true).setAttribute("database", database);
        request.getSession(true).setAttribute("monitoring", monitoring);

        return RestApplication.returnJsonObject(request, new JSONObject().put("status", "Loaded"));
    }

    /**
     * Generates the attack graph and initializes the main objects for other API calls
     * (database, attack graph, attack paths,...).
     * Load the objects from the XML file POST through a form describing the whole network topology
     *
     * @param request             the HTTP request
     * @param uploadedInputStream The input stream of the XML file
     * @param fileDetail          The file detail object
     * @param body                The body object relative to the XML file
     * @return the HTTP response
     * @throws Exception
     */
    @POST
    @Path("/initialize")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response initializeFromXMLFile(@Context HttpServletRequest request,
                                          @FormDataParam("file") InputStream uploadedInputStream,
                                          @FormDataParam("file") FormDataContentDisposition fileDetail,
                                          @FormDataParam("file") FormDataBodyPart body) throws Exception {

        if (!body.getMediaType().equals(MediaType.APPLICATION_XML_TYPE) && !body.getMediaType().equals(MediaType.TEXT_XML_TYPE)
                && !body.getMediaType().equals(MediaType.TEXT_PLAIN_TYPE))
            return RestApplication.returnErrorMessage(request, "The file is not an XML file");
        String xmlFileString = IOUtils.toString(uploadedInputStream, "UTF-8");

        return initializeFromXMLText(request, xmlFileString);

    }

    /**
     * Get the XML topology
     *
     * @param request the HTTP Request
     * @return the HTTP Response
     */
    @GET
    @Path("/topology")
    @Produces(MediaType.APPLICATION_XML)
    public Response getTopology(@Context HttpServletRequest request) {
        Monitoring monitoring = ((Monitoring) request.getSession(true).getAttribute("monitoring"));

        if (monitoring == null) {
            return Response.ok("The monitoring object is empty. Did you forget to " +
                    "initialize it ?").build();
        }
        return Response.ok(new XMLOutputter(Format.getPrettyFormat()).outputString(monitoring.getInformationSystem().toDomXMLElement())).build();
    }

    /**
     * Get the hosts list
     *
     * @param request the HTTP Request
     * @return the HTTP Response
     */
    @GET
    @Path("host/list")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getHostList(@Context HttpServletRequest request) {
        Monitoring monitoring = ((Monitoring) request.getSession(true).getAttribute("monitoring"));

        if (monitoring == null) {
            return RestApplication.returnErrorMessage(request, "The monitoring object is empty. Did you forget to " +
                    "initialize it ?");
        }
        return RestApplication.returnJsonObject(request, monitoring.getInformationSystem().getHostsListJson());
    }

    @OPTIONS
    @Path("/host/list")
    public Response setHostListOptions(@Context HttpServletRequest request) {
        return RestApplication.returnJsonObject(request, new JSONObject());
    }

    /**
     * Post the hosts list with their new security requirements
     *
     * @param request the HTTP Request
     * @return the HTTP Response
     */
    @POST
    @Path("host/list")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response setHostList(@Context HttpServletRequest request, String jsonString) {
        Monitoring monitoring = ((Monitoring) request.getSession(true).getAttribute("monitoring"));

        if (monitoring == null) {
            return RestApplication.returnErrorMessage(request, "The monitoring object is empty. Did you forget to " +
                    "initialize it ?");
        }
        JSONObject json = new JSONObject(jsonString);
        try {
            InformationSystemManagement.loadHostsSecurityRequirementsFromJson(monitoring, json);
            return RestApplication.returnJsonObject(request, new JSONObject());
        } catch (Exception e) {
            return RestApplication.returnErrorMessage(request, e.getMessage());
        }


    }

    /**
     * Get the attack paths list
     *
     * @param request the HTTP Request
     * @return the HTTP Response
     */
    @GET
    @Path("attack_path/list")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getList(@Context HttpServletRequest request) {
        Monitoring monitoring = ((Monitoring) request.getSession(true).getAttribute("monitoring"));

        if (monitoring == null) {
            return RestApplication.returnErrorMessage(request, "The monitoring object is empty. Did you forget to " +
                    "initialize it ?");
        }

        Element attackPathsXML = AttackPathManagement.getAttackPathsXML(monitoring);
        XMLOutputter output = new XMLOutputter(Format.getPrettyFormat());
        return RestApplication.returnJsonObject(request, XML.toJSONObject(output.outputString(attackPathsXML)));

    }

    /**
     * Get the number of attack paths
     *
     * @param request the HTTP Request
     * @return the HTTP Response
     */
    @GET
    @Path("attack_path/number")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getNumber(@Context HttpServletRequest request) {
        Monitoring monitoring = ((Monitoring) request.getSession(true).getAttribute("monitoring"));

        if (monitoring == null) {
            return RestApplication.returnErrorMessage(request, "The monitoring object is empty. Did you forget to " +
                    "initialize it ?");
        }

        return RestApplication.returnJsonObject(request, new JSONObject().put("number", monitoring.getAttackPathList().size()));
    }

    /**
     * Get one attack path (id starting from 0)
     *
     * @param request the HTTP Request
     * @param id      the id of the attack path to get
     * @return the HTTP Response
     */
    @GET
    @Path("attack_path/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAttackPath(@Context HttpServletRequest request, @PathParam("id") int id) {
        Monitoring monitoring = ((Monitoring) request.getSession(true).getAttribute("monitoring"));

        if (monitoring == null) {
            return RestApplication.returnErrorMessage(request, "The monitoring object is empty. Did you forget to " +
                    "initialize it ?");
        }

        int numberAttackPaths = monitoring.getAttackPathList().size();

        if (id >= numberAttackPaths) {
            return RestApplication.returnErrorMessage(request, "The attack path " + id + " does not exist. There are only" +
                    numberAttackPaths + " attack paths (0 to " +
                    (numberAttackPaths - 1) + ")");
        }

        Element attackPathXML = AttackPathManagement.getAttackPathXML(monitoring, id);
        XMLOutputter output = new XMLOutputter(Format.getPrettyFormat());

        return RestApplication.returnJsonObject(request, XML.toJSONObject(output.outputString(attackPathXML)));
    }

    /**
     * Get one attack path (id starting from 0) in its topological form
     *
     * @param request the HTTP Request
     * @param id      the id of the attack path to get
     * @return the HTTP Response
     */
    @GET
    @Path("attack_path/{id}/topological")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTopologicalAttackPath(@Context HttpServletRequest request, @PathParam("id") int id) {
        Monitoring monitoring = ((Monitoring) request.getSession(true).getAttribute("monitoring"));

        if (monitoring == null) {
            return RestApplication.returnErrorMessage(request, "The monitoring object is empty. Did you forget to " +
                    "initialize it ?");
        }

        int numberAttackPaths = monitoring.getAttackPathList().size();

        if (id >= numberAttackPaths) {
            return RestApplication.returnErrorMessage(request, "The attack path " + id + " does not exist. There are only" +
                    numberAttackPaths + " attack paths (0 to " +
                    (numberAttackPaths - 1) + ")");
        }

        return RestApplication.returnJsonObject(request, AttackPathManagement.getAttackPathTopologicalJson(monitoring, id));
    }

    /**
     * Compute and return the remediations for an attack path
     *
     * @param request the HTTP Request
     * @param id      the identifier of the attack path for which the remediations will be computed
     * @return the HTTP Response
     */
    @GET
    @Path("attack_path/{id}/remediations")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAttackPathRemediations(@Context HttpServletRequest request, @PathParam("id") int id) {
        Monitoring monitoring = ((Monitoring) request.getSession(true).getAttribute("monitoring"));
        Database db = ((Database) request.getSession(true).getAttribute("database"));

        if (monitoring == null) {
            return RestApplication.returnErrorMessage(request, "The monitoring object is empty. Did you forget to " +
                    "initialize it ?");
        }

        if (db == null) {
            return RestApplication.returnErrorMessage(request, "The database object is empty. Did you forget to " +
                    "initialize it ?");
        }

        int numberAttackPaths = monitoring.getAttackPathList().size();

        if (id >= numberAttackPaths) {
            return RestApplication.returnErrorMessage(request, "The attack path " + id + " does not exist. There are only" +
                    numberAttackPaths + " attack paths (0 to " +
                    (numberAttackPaths - 1) + ")");
        }

        Element remediationXML = AttackPathManagement.getRemediationXML(monitoring, id, db);
        XMLOutputter output = new XMLOutputter(Format.getPrettyFormat());

        return RestApplication.returnJsonObject(request, XML.toJSONObject(output.outputString(remediationXML)));
    }

    /**
     * Simulate the remediation id_remediation of the path id, and compute the new attack graph
     *
     * @param request        the HTTP Request
     * @param id             the identifier of the attack path for which the remediations will be computed
     * @param id_remediation the identifier of the remediation to simulate
     * @return the HTTP Response
     */
    @GET
    @Path("attack_path/{id}/remediation/{id-remediation}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response simulateRemediationInAttackGraph(@Context HttpServletRequest request, @PathParam("id") int id, @PathParam("id-remediation") int id_remediation) throws Exception {
        Monitoring monitoring = ((Monitoring) request.getSession(true).getAttribute("monitoring"));
        Database db = ((Database) request.getSession(true).getAttribute("database"));

        if (monitoring == null) {
            return RestApplication.returnErrorMessage(request, "The monitoring object is empty. Did you forget to " +
                    "initialize it ?");
        }

        if (db == null) {
            return RestApplication.returnErrorMessage(request, "The database object is empty. Did you forget to " +
                    "initialize it ?");
        }

        int numberAttackPaths = monitoring.getAttackPathList().size();

        if (id >= numberAttackPaths) {
            return RestApplication.returnErrorMessage(request, "The attack path " + id + " does not exist. There are only" +
                    numberAttackPaths + " attack paths (0 to " +
                    (numberAttackPaths - 1) + ")");
        }

        List<DeployableRemediation> remediations = monitoring.getAttackPathList().get(id).getDeployableRemediations(monitoring.getInformationSystem(), db.getConn(), monitoring.getPathToCostParametersFolder());

        int numberRemediations = remediations.size();

        if (id_remediation >= numberRemediations) {
            return RestApplication.returnErrorMessage(request, "The remediation " + id_remediation + " does not exist. There are only" +
                    numberRemediations + " remediations (0 to " +
                    (numberRemediations - 1) + ")");
        }
        DeployableRemediation deployableRemediation = remediations.get(id_remediation);

        AttackGraph simulatedAttackGraph;

        try {
            simulatedAttackGraph = monitoring.getAttackGraph().clone();

            for (int i = 0; i < deployableRemediation.getActions().size(); i++) {
                Vertex vertexToDelete = deployableRemediation.getActions().get(i).getRemediationAction().getRelatedVertex();
                simulatedAttackGraph.deleteVertex(simulatedAttackGraph.vertices.get(vertexToDelete.id));
            }

            AttackPathManagement.scoreAttackPaths(simulatedAttackGraph, monitoring.getAttackGraph().getNumberOfVertices());

            Element attackGraphXML = simulatedAttackGraph.toDomElement();
            XMLOutputter output = new XMLOutputter(Format.getPrettyFormat());
            return RestApplication.returnJsonObject(request, XML.toJSONObject(output.outputString(attackGraphXML)));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return RestApplication.returnErrorMessage(request, "Error during the simulation of the remediation.");

    }

    /**
     * Validate that the remediation id_remediation of the path id has been applied
     *
     * @param request        the HTTP Request
     * @param id             the identifier of the attack path for which the remediations have been computed
     * @param id_remediation the identifier of the remediation to validate
     * @return the HTTP Response
     */
    @GET
    @Path("attack_path/{id}/remediation/{id-remediation}/validate")
    @Produces(MediaType.APPLICATION_JSON)
    public Response validateRemediationInAttackGraph(@Context HttpServletRequest request, @PathParam("id") int id, @PathParam("id-remediation") int id_remediation) {
        Monitoring monitoring = ((Monitoring) request.getSession(true).getAttribute("monitoring"));
        Database db = ((Database) request.getSession(true).getAttribute("database"));

        if (monitoring == null) {
            return RestApplication.returnErrorMessage(request, "The monitoring object is empty. Did you forget to " +
                    "initialize it ?");
        }

        if (db == null) {
            return RestApplication.returnErrorMessage(request, "The database object is empty. Did you forget to " +
                    "initialize it ?");
        }

        int numberAttackPaths = monitoring.getAttackPathList().size();

        if (id >= numberAttackPaths) {
            return RestApplication.returnErrorMessage(request, "The attack path " + id + " does not exist. There are only" +
                    numberAttackPaths + " attack paths (0 to " +
                    (numberAttackPaths - 1) + ")");
        }

        List<DeployableRemediation> remediations;
        try {
            remediations = monitoring.getAttackPathList().get(id).getDeployableRemediations(monitoring.getInformationSystem(), db.getConn(), monitoring.getPathToCostParametersFolder());
        } catch (Exception e) {
            return RestApplication.returnErrorMessage(request, "Error during the computation of the remediations:" + e.getMessage());
        }

        int numberRemediations = remediations.size();

        if (id_remediation >= numberRemediations) {
            return RestApplication.returnErrorMessage(request, "The remediation " + id_remediation + " does not exist. There are only" +
                    numberRemediations + " remediations (0 to " +
                    (numberRemediations - 1) + ")");
        }
        DeployableRemediation deployableRemediation = remediations.get(id_remediation);

        try {
            deployableRemediation.validate(monitoring.getInformationSystem());
        } catch (Exception e) {
            return RestApplication.returnErrorMessage(request, "Error during the validation of the remediations:" + e.getMessage());
        }

        return RestApplication.returnSuccessMessage(request, "The remediation has been validated.");

    }

    /**
     * Get the whole attack graph
     *
     * @param request the HTTP Request
     * @return the HTTP Response
     */
    @GET
    @Path("attack_graph")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAttackGraph(@Context HttpServletRequest request) {
        Monitoring monitoring = ((Monitoring) request.getSession(true).getAttribute("monitoring"));

        if (monitoring == null) {
            return RestApplication.returnErrorMessage(request, "The monitoring object is empty. Did you forget to " +
                    "initialize it ?");
        }

        Element attackGraphXML = monitoring.getAttackGraph().toDomElement();
        XMLOutputter output = new XMLOutputter(Format.getPrettyFormat());
        return RestApplication.returnJsonObject(request, XML.toJSONObject(output.outputString(attackGraphXML)));
    }

    /**
     * Get the attack graph score
     *
     * @param request the HTTP Request
     * @return the HTTP Response
     */
    @GET
    @Path("attack_graph/score")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAttackGraphScore(@Context HttpServletRequest request) {
        Monitoring monitoring = ((Monitoring) request.getSession(true).getAttribute("monitoring"));

        if (monitoring == null) {
            return RestApplication.returnErrorMessage(request, "The monitoring object is empty. Did you forget to " +
                    "initialize it ?");
        }

        return RestApplication.returnJsonObject(request, new JSONObject().put("score", monitoring.getAttackGraph().globalScore));
    }

    /**
     * Get the topological representation of the whole attack graph
     *
     * @param request the HTTP Request
     * @return the HTTP Response
     */
    @GET
    @Path("attack_graph/topological")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getTopologicalAttackGraph(@Context HttpServletRequest request) {
        Monitoring monitoring = ((Monitoring) request.getSession(true).getAttribute("monitoring"));

        if (monitoring == null) {
            return RestApplication.returnErrorMessage(request, "The monitoring object is empty. Did you forget to " +
                    "initialize it ?");
        }

        return RestApplication.returnJsonObject(request, AttackPathManagement.getAttackGraphTopologicalJson(monitoring));
    }


    /**
     * Receive alerts in IDMEF format and add them into a local queue file,
     * before releasing them when the client requests it.
     *
     * @param request             the HTTP request
     * @param uploadedInputStream The input stream of the IDMEF XML file
     * @param fileDetail          The file detail object
     * @param body                The body object relative to the XML file
     * @return the HTTP response
     * @throws Exception
     */
    @POST
    @Path("/idmef/add")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Response addIDMEFAlerts(@Context HttpServletRequest request,
                                   @FormDataParam("file") InputStream uploadedInputStream,
                                   @FormDataParam("file") FormDataContentDisposition fileDetail,
                                   @FormDataParam("file") FormDataBodyPart body) throws Exception {

        if (!body.getMediaType().equals(MediaType.APPLICATION_XML_TYPE) && !body.getMediaType().equals(MediaType.TEXT_XML_TYPE)
                && !body.getMediaType().equals(MediaType.TEXT_PLAIN_TYPE))
            return RestApplication.returnErrorMessage(request, "The file is not an XML file");

        String xmlFileString = IOUtils.toString(uploadedInputStream, "UTF-8");
        return addIDMEFAlertsFromXMLText(request, xmlFileString);
    }

    /**
     * Receive alerts in IDMEF format and add them into a local queue file,
     * before releasing them when the client requests it.
     *
     * @param request the HTTP request
     * @return the HTTP response
     * @throws Exception
     */
    @POST
    @Path("/idmef/add")
    @Consumes(MediaType.APPLICATION_XML)
    @Produces(MediaType.APPLICATION_JSON)
    public Response addIDMEFAlertsFromXMLText(@Context HttpServletRequest request, String xmlString) throws Exception {
        if (xmlString == null || xmlString.isEmpty())
            return RestApplication.returnErrorMessage(request, "The input text string is empty.");

        IDMEFManagement.loadIDMEFAlertsFromXML(xmlString);
        return RestApplication.returnSuccessMessage(request, "IDMEF alerts added successfully");
    }

    /**
     * Get alerts in JSON format and set them as "sent" into a local queue file.
     *
     * @param request the HTTP Request
     * @return the HTTP Response
     */
    @GET
    @Path("/idmef/alerts")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getAlerts(@Context HttpServletRequest request) throws IOException, ClassNotFoundException {
        Monitoring monitoring = ((Monitoring) request.getSession(true).getAttribute("monitoring"));

        if (monitoring == null) {
            return RestApplication.returnErrorMessage(request, "The monitoring object is empty. Did you forget to " +
                    "initialize it ?");
        }

        return RestApplication.returnJsonObject(request, IDMEFManagement.getAlerts(monitoring.getInformationSystem()));
    }
}
