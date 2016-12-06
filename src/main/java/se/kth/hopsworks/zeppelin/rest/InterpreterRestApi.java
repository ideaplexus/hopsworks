/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package se.kth.hopsworks.zeppelin.rest;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.ejb.EJB;
import javax.enterprise.context.RequestScoped;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.apache.zeppelin.conf.ZeppelinConfiguration;
import org.apache.zeppelin.interpreter.Interpreter;
import org.apache.zeppelin.interpreter.Interpreter.RegisteredInterpreter;
import org.apache.zeppelin.interpreter.InterpreterException;
import org.apache.zeppelin.interpreter.InterpreterSetting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import se.kth.bbc.project.Project;
import se.kth.hopsworks.rest.AppException;
import se.kth.hopsworks.zeppelin.rest.message.NewInterpreterSettingRequest;
import se.kth.hopsworks.zeppelin.rest.message.UpdateInterpreterSettingRequest;
import se.kth.hopsworks.zeppelin.server.JsonResponse;
import se.kth.hopsworks.zeppelin.server.ZeppelinConfig;
import se.kth.hopsworks.zeppelin.util.ZeppelinResource;

import com.google.gson.Gson;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.logging.Level;
import javax.faces.bean.ManagedProperty;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.yarn.api.records.ApplicationReport;
import org.apache.hadoop.yarn.client.api.YarnClient;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.zeppelin.dep.Repository;
import org.apache.zeppelin.interpreter.InterpreterFactory;
import org.sonatype.aether.RepositoryException;
import org.sonatype.aether.repository.RemoteRepository;
import se.kth.bbc.jobs.jobhistory.YarnApplicationstate;
import se.kth.bbc.jobs.jobhistory.YarnApplicationstateFacade;
import se.kth.bbc.jobs.yarn.YarnRunner;
import se.kth.bbc.project.ProjectTeam;
import se.kth.bbc.project.ProjectTeamFacade;
import se.kth.bbc.security.ua.JobAdministration;
import se.kth.hopsworks.filters.AllowedRoles;
import se.kth.hopsworks.hdfsUsers.controller.HdfsUsersController;
import se.kth.hopsworks.user.model.Users;
import se.kth.hopsworks.users.UserFacade;
import se.kth.hopsworks.util.Settings;
import se.kth.hopsworks.zeppelin.server.ZeppelinConfigFactory;
import se.kth.hopsworks.zeppelin.util.LivyMsg;
import se.kth.hopsworks.zeppelin.util.TicketContainer;
import se.kth.hopsworks.zeppelin.util.ZeppelinInterpreterConfFacade;

/**
 * Interpreter Rest API
 * <p>
 */
@RequestScoped
public class InterpreterRestApi {

  Logger logger = LoggerFactory.getLogger(InterpreterRestApi.class);
  private InterpreterFactory interpreterFactory;
  private Project project;
  private ZeppelinConfig zeppelinConf;
  private String roleInProject;
  private Users user;

  @EJB
  private ZeppelinResource zeppelinResource;
  @EJB
  private ZeppelinConfigFactory zeppelinConfFactory;
  @EJB
  private ProjectTeamFacade teambean;
  @EJB
  private HdfsUsersController hdfsUserBean;
  @EJB
  private YarnApplicationstateFacade appStateBean;
  @EJB
  private UserFacade userFacade;
  @EJB
  private Settings settings;
  @EJB
  private ZeppelinInterpreterConfFacade zeppelinInterpreterConfFacade;
  
  Gson gson = new Gson();

  public InterpreterRestApi() {
  }

  public void setParms(Project project, Users user, String userRole,
          ZeppelinConfig zeppelinConf) {
    this.project = project;
    this.user = user;
    this.zeppelinConf = zeppelinConf;
    this.roleInProject = userRole;
    this.interpreterFactory = zeppelinConf.getReplFactory();

  }

  /**
   * List all interpreter settings
   * <p/>
   * @return
   */
  @GET
  @Path("setting")
  public Response listSettings() {
    List<InterpreterSetting> interpreterSettings;
    interpreterSettings = interpreterFactory.get();
    return new JsonResponse(Status.OK, "", interpreterSettings).build();
  }

  /**
   * Add new interpreter setting
   *
   * @param message
   * @return
   * @throws InterpreterException
   */
  @POST
  @Path("setting")
  public Response newSettings(String message) {
    try {
      NewInterpreterSettingRequest request = gson.fromJson(message,
              NewInterpreterSettingRequest.class);
      Properties p = new Properties();
      p.putAll(request.getProperties());
      InterpreterSetting interpreterSetting = interpreterFactory.add(request.
              getName(),
              request.getGroup(),
              request.getDependencies(),
              request.getOption(),
              p);
      persistToDB();
      logger.info("new setting created with {}", interpreterSetting.id());
      return new JsonResponse(Status.CREATED, "", interpreterSetting).build();
    } catch (InterpreterException e) {
      logger.error("Exception in InterpreterRestApi while creating ", e);
      return new JsonResponse(
              Status.NOT_FOUND,
              e.getMessage(),
              ExceptionUtils.getStackTrace(e)).build();
    } catch (IOException | RepositoryException e) {
      logger.error("Exception in InterpreterRestApi while creating ", e);
      return new JsonResponse(
              Status.INTERNAL_SERVER_ERROR,
              e.getMessage(),
              ExceptionUtils.getStackTrace(e)).build();
    }
  }

  /**
   *
   * @param message
   * @param settingId
   * @return
   */
  @PUT
  @Path("setting/{settingId}")
  public Response updateSetting(String message,
          @PathParam("settingId") String settingId) {
    logger.info("Update interpreterSetting {}", settingId);

    try {
      UpdateInterpreterSettingRequest request = gson.fromJson(message,
              UpdateInterpreterSettingRequest.class);
      interpreterFactory.setPropertyAndRestart(settingId,
              request.getOption(),
              request.getProperties(),
              request.getDependencies());
    } catch (InterpreterException e) {
      logger.error("Exception in InterpreterRestApi while updateSetting ", e);
      return new JsonResponse(
              Status.NOT_FOUND, e.getMessage(), ExceptionUtils.getStackTrace(e)).
              build();
    } catch (IOException | RepositoryException e) {
      logger.error("Exception in InterpreterRestApi while updateSetting ", e);
      return new JsonResponse(
              Status.INTERNAL_SERVER_ERROR, e.getMessage(), ExceptionUtils.
              getStackTrace(e)).build();
    }
    InterpreterSetting setting = interpreterFactory.get(settingId);
    //Persist json to the database
    persistToDB();

    if (setting == null) {
      return new JsonResponse(Status.NOT_FOUND, "", settingId).build();
    }
    return new JsonResponse(Status.OK, "", setting).build();
  }
  
  private void persistToDB() {
    try {
      String s = readConfigFile(new File(zeppelinConf.getConfDirPath()
              + ZeppelinConfig.INTERPRETER_JSON));
      zeppelinInterpreterConfFacade.create(project.getName(), s);
    } catch (IOException ex) {
      java.util.logging.Logger.getLogger(InterpreterRestApi.class.getName()).
              log(Level.SEVERE, null, ex);
    }
  }

  private String readConfigFile(File path) throws IOException {
    // write contents to file as text, not binary data
    if (!path.exists()) {
        throw new IOException("Problem creating file: " + path);
    }
    return new String(Files.readAllBytes(path.toPath()));
  }

  /**
   * Remove interpreter setting
   *
   * @param settingId
   * @return
   * @throws java.io.IOException
   */
  @DELETE
  @Path("setting/{settingId}")
  public Response removeSetting(@PathParam("settingId") String settingId) throws
          IOException {
    logger.info("Remove interpreterSetting {}", settingId);
    interpreterFactory.remove(settingId);
    persistToDB();
    return new JsonResponse(Status.OK).build();
  }

  /**
   * Restart interpreter setting
   *
   * @param settingId
   * @return
   */
  @PUT
  @Path("setting/restart/{settingId}")
  public Response restartSetting(@PathParam("settingId") String settingId) {
    logger.info("Restart interpreterSetting {}", settingId);
    try {
      interpreterFactory.restart(settingId);
    } catch (InterpreterException e) {
      logger.error("Exception in InterpreterRestApi while restartSetting ", e);
      return new JsonResponse(
              Status.NOT_FOUND, e.getMessage(), ExceptionUtils.getStackTrace(e)).
              build();
    }
    InterpreterSetting setting = interpreterFactory.get(settingId);
    if (setting == null) {
      return new JsonResponse(Status.NOT_FOUND, "", settingId).build();
    }
    int timeout = zeppelinConf.getConf().getInt(
            ZeppelinConfiguration.ConfVars.ZEPPELIN_INTERPRETER_CONNECT_TIMEOUT);
    long startTime = System.currentTimeMillis();
    long endTime;
    while (zeppelinResource.isInterpreterRunning(setting, project)) {
      endTime = System.currentTimeMillis();
      if ((endTime - startTime) > (timeout * 2)) {
        break;
      }
    }
    if (zeppelinResource.isInterpreterRunning(setting, project)) {
      zeppelinResource.forceKillInterpreter(setting, project);
    }
    InterpreterDTO interpreter = new InterpreterDTO(setting,
            !zeppelinResource.isInterpreterRunning(setting, project));
    return new JsonResponse(Status.OK, "", interpreter).build();
  }

  /**
   * Stop livy session
   *
   * @param sessionId
   * @param settingId
   * @return
   * @throws se.kth.hopsworks.rest.AppException
   */
  @DELETE
  @Path("/livy/sessions/delete/{settingId}/{sessionId}")
  public Response stopSession(@PathParam("settingId") String settingId,
          @PathParam("sessionId") int sessionId) throws AppException {
    logger.info("Restart interpreterSetting {}", settingId);
    InterpreterSetting setting = zeppelinConf.getReplFactory().get(settingId);
    LivyMsg.Session session = zeppelinResource.getLivySession(sessionId);
    if (session == null) {
      return new JsonResponse(Response.Status.NOT_FOUND, "Session '" + sessionId
              + "' not found.").build();
    }
    String projName = hdfsUserBean.getProjectName(session.getProxyUser());
    String username = hdfsUserBean.getUserName(session.getProxyUser());
    if (!this.project.getName().equals(projName)) {
      throw new AppException(Status.BAD_REQUEST.getStatusCode(),
              "You can't stop sessions in another project.");
    }
    if (!this.user.getUsername().equals(username) && this.roleInProject.equals(
            AllowedRoles.DATA_SCIENTIST)) {
      throw new AppException(Status.BAD_REQUEST.getStatusCode(),
              "You are not authorized to stop this session.");
    }
//    List<YarnApplicationstate> yarnAppStates;
//    yarnAppStates = appStateBean.
//            findByAppuserAndAppState(session.getProxyUser(),
//                    "RUNNING");
    List<JobAdministration.YarnApplicationReport> yarnAppStates;
    yarnAppStates = fetchJobs(session.getProxyUser());
    try {
      zeppelinResource.deleteLivySession(sessionId);
      if (this.user.getUsername().equals(username) && yarnAppStates.size() == 1) {
        zeppelinConf.getReplFactory().restart(settingId);
      } else if (yarnAppStates.size() == 1) {
        Users u = userFacade.findByUsername(username);
        if (u == null) {
          throw new AppException(Status.BAD_REQUEST.getStatusCode(),
                  "The owner of the session was not found.");
        }
        ZeppelinConfig zConf = zeppelinConfFactory.getZeppelinConfig(
                this.project.
                getName(), u.getEmail());
        if (zConf.getReplFactory() != null) {
          zConf.getReplFactory().restart(settingId);
        }
      }
    } catch (InterpreterException e) {
      logger.warn("Could not close interpreter.", e);
      throw new AppException(Status.BAD_REQUEST.getStatusCode(),
              "Could not close interpreter. Make sure it is not running.");
    }

    int timeout = zeppelinConf.getConf().getInt(
            ZeppelinConfiguration.ConfVars.ZEPPELIN_INTERPRETER_CONNECT_TIMEOUT);
    long startTime = System.currentTimeMillis();
    long endTime;
    while (zeppelinResource.isLivySessionAlive(sessionId)) {
      endTime = System.currentTimeMillis();
      if ((endTime - startTime) > (timeout * 2)) {
        break;
      }
    }
    int res = zeppelinResource.deleteLivySession(sessionId);
    if (res != Response.Status.NOT_FOUND.getStatusCode() && res
            != Response.Status.OK.getStatusCode()) {
      return new JsonResponse(Response.Status.EXPECTATION_FAILED,
              "Could not stop session '" + sessionId
              + "'").build();
    }

//    InterpreterDTO interpreter = new InterpreterDTO(setting,
//            !zeppelinResource.isInterpreterRunning(setting, project),
//            getRunningLivySessions(this.project));
    InterpreterDTO interpreter = new InterpreterDTO(setting,
            !zeppelinResource.isInterpreterRunning(setting, project),
            getRunningLivySessionsfromYarnClient(this.project));
    return new JsonResponse(Status.OK, "Deleted ", interpreter).build();
  }

  /**
   * List all available interpreters by group
   * <p/>
   * @param message
   * @return
   */
  @GET
  public Response listInterpreter() {
    Map<String, RegisteredInterpreter> m = Interpreter.registeredInterpreters;
    return new JsonResponse(Status.OK, "", m).build();
  }

  /**
   * List of dependency resolving repositories
   *
   * @return
   */
  @GET
  @Path("repository")
  public Response listRepositories() {
    List<RemoteRepository> interpreterRepositories;
    interpreterRepositories = interpreterFactory.getRepositories();
    return new JsonResponse(Status.OK, "", interpreterRepositories).build();
  }

  /**
   * Add new repository
   *
   * @param message
   * @return
   */
  @POST
  @Path("repository")
  public Response addRepository(String message) {
    try {
      Repository request = gson.fromJson(message, Repository.class);
      interpreterFactory.addRepository(
              request.getId(),
              request.getUrl(),
              request.isSnapshot(),
              request.getAuthentication());
      persistToDB();
      logger.info("New repository {} added", request.getId());
    } catch (Exception e) {
      logger.error("Exception in InterpreterRestApi while adding repository ",
              e);
      return new JsonResponse(
              Status.INTERNAL_SERVER_ERROR, e.getMessage(), ExceptionUtils.
              getStackTrace(e)).build();
    }
    return new JsonResponse(Status.CREATED).build();
  }

  /**
   * Delete repository
   *
   * @param repoId
   * @return
   */
  @DELETE
  @Path("repository/{repoId}")
  public Response removeRepository(@PathParam("repoId") String repoId) {
    logger.info("Remove repository {}", repoId);
    try {
      interpreterFactory.removeRepository(repoId);
      persistToDB();
    } catch (Exception e) {
      logger.error("Exception in InterpreterRestApi while removing repository ",
              e);
      return new JsonResponse(
              Status.INTERNAL_SERVER_ERROR, e.getMessage(), ExceptionUtils.
              getStackTrace(e)).build();
    }
    return new JsonResponse(Status.OK).build();
  }

  /**
   * list interpreters with status(running or not).
   * <p/>
   * @return nothing if successful.
   * @throws se.kth.hopsworks.rest.AppException
   */
  @GET
  @Path("interpretersWithStatus")
  public Response getinterpretersWithStatus()
          throws AppException {
    Map<String, InterpreterDTO> interpreters = interpreters(project);
    return new JsonResponse(Status.OK, "", interpreters).build();
  }

  private Map<String, InterpreterDTO> interpreters(Project project) throws
          AppException {
    Map<String, InterpreterDTO> interpreterDTOs = new HashMap<>();
    List<InterpreterSetting> interpreterSettings;
    interpreterSettings = zeppelinConf.getReplFactory().get();
    InterpreterDTO interpreterDTO;
    for (InterpreterSetting interpreter : interpreterSettings) {
      interpreterDTO = new InterpreterDTO(interpreter, !zeppelinResource.
              isInterpreterRunning(interpreter, project));
      interpreterDTOs.put(interpreter.getGroup(), interpreterDTO);
      if (interpreter.getGroup().contains("livy")) {
        //interpreterDTO.setSessions(getRunningLivySessions(project));
        interpreterDTO.setSessions(getRunningLivySessionsfromYarnClient(project));
      }
    }
    return interpreterDTOs;
  }

  private List<LivyMsg.Session> getRunningLivySessions(Project project) {
    List<LivyMsg.Session> sessions = new ArrayList<>();
    List<ProjectTeam> projectTeam;
    List<YarnApplicationstate> yarnAppStates;
    String hdfsUsername;
    int id;
    projectTeam = teambean.findMembersByProject(project);
    for (ProjectTeam member : projectTeam) {
      hdfsUsername = hdfsUserBean.getHdfsUserName(project, member.getUser());
      yarnAppStates = appStateBean.findByAppuserAndAppState(hdfsUsername,
              "RUNNING");
      for (YarnApplicationstate state : yarnAppStates) {
        try {
          id = Integer.parseInt(state.getAppname().substring(
                  "livy-session-".length()));
        } catch (NumberFormatException e) {
          continue;
        }
        if (state.getAppname().startsWith("livy-session-")) {
          sessions.add(new LivyMsg.Session(id, member.
                  getUser().getEmail()));
        }
      }
    }
    return sessions;
  }

  /**
   * Restarts zeppelin by cleaning the cache for the
   *
   * @return
   * @throws AppException
   */
  @GET
  @Path("restart")
  public Response restart() throws AppException {
    Long timeSinceLastRestart;
    Long lastRestartTime = zeppelinConfFactory.getLastRestartTime(this.project.
            getName());
    if (lastRestartTime != null) {
      timeSinceLastRestart = System.currentTimeMillis() - lastRestartTime;
      if (timeSinceLastRestart < 60000 * 1) {
        throw new AppException(Status.BAD_REQUEST.getStatusCode(),
                "This service has been restarted recently. "
                + "Please wait a few minutes before trying again.");
      }
    }
    Map<String, InterpreterDTO> interpreterDTOMap = interpreters(this.project);
    InterpreterDTO interpreterDTO;
    for (String key : interpreterDTOMap.keySet()) {
      interpreterDTO = interpreterDTOMap.get(key);
      if (interpreterDTO.isNotRunning()) {
        continue;
      }
      if (interpreterDTO.getGroup().contains("livy")) {
        for (LivyMsg.Session session : interpreterDTO.getSessions()) {
          stopSession(interpreterDTO.getId(), session.getId());
        }
      } else {
        restartSetting(interpreterDTO.getId());
      }
    }
    zeppelinConfFactory.removeFromCache(this.project.getName());
    List<ProjectTeam> projectTeam;
    projectTeam = teambean.findMembersByProject(this.project);
    for (ProjectTeam member : projectTeam) {
      zeppelinConfFactory.removeFromCache(this.project.getName(), member.
              getUser().getEmail());
      TicketContainer.instance.invalidate(member.getUser().getEmail());
    }

    return new JsonResponse(Status.OK, "Cache cleared.").build();
  }
  
  //temporary until YarnApplicationstate table fixed
  private List<LivyMsg.Session> getRunningLivySessionsfromYarnClient(
          Project project) {
    List<LivyMsg.Session> sessions = new ArrayList<>();
    List<ProjectTeam> projectTeam;
    List<JobAdministration.YarnApplicationReport> yarnAppReport;
    yarnAppReport = fetchJobs();
    String hdfsUsername;
    int id;
    projectTeam = teambean.findMembersByProject(project);
    for (ProjectTeam member : projectTeam) {
      hdfsUsername = hdfsUserBean.getHdfsUserName(project, member.getUser());
      for (JobAdministration.YarnApplicationReport report : yarnAppReport) {
        if (hdfsUsername.equals(report.getUser()) && report.getName().
                startsWith("livy-session-") && report.getState().equals(
                "RUNNING")) {
          try {
            id = Integer.parseInt(report.getName().substring(
                    "livy-session-".length()));
          } catch (NumberFormatException e) {
            continue;
          }
          sessions.add(new LivyMsg.Session(id, member.
                  getUser().getEmail()));
        }
      }
    }
    return sessions;
  }
  
  private List<JobAdministration.YarnApplicationReport> fetchJobs() {
      JobAdministration jobAdmin = new JobAdministration();
      List<JobAdministration.YarnApplicationReport> reports = new ArrayList<>();
      YarnClient client = YarnClient.createYarnClient();
      Configuration conf = setConfiguration(settings.getHadoopDir());
      client.init(conf);
      client.start();
    try {
      //Create our custom YarnApplicationReport Pojo
      for (ApplicationReport appReport : client.getApplications()) {
        reports.add(jobAdmin.new YarnApplicationReport(appReport.getApplicationId().
                toString(),
                appReport.getName(), appReport.getUser(), appReport.
                getStartTime(), appReport.getFinishTime(), appReport.
                getApplicationId().getClusterTimestamp(),
                appReport.getApplicationId().getId(), appReport.
                getYarnApplicationState().name()));
      }
    } catch (YarnException | IOException ex) {
      logger.error("",ex);
    }
    return reports;
  }
  
  private List<JobAdministration.YarnApplicationReport> fetchJobs(String username) {
      JobAdministration jobAdmin = new JobAdministration();
      List<JobAdministration.YarnApplicationReport> reports = new ArrayList<>();
      YarnClient client = YarnClient.createYarnClient();
      Configuration conf = setConfiguration(settings.getHadoopDir());
      client.init(conf);
      client.start();
    try {
      //Create our custom YarnApplicationReport Pojo
      for (ApplicationReport appReport : client.getApplications()) {
        if (username.equals(appReport.getUser()) && appReport.
                getYarnApplicationState().name().equals("RUNNING")) {
          reports.add(jobAdmin.new YarnApplicationReport(appReport.getApplicationId().
                toString(),
                appReport.getName(), appReport.getUser(), appReport.
                getStartTime(), appReport.getFinishTime(), appReport.
                getApplicationId().getClusterTimestamp(),
                appReport.getApplicationId().getId(), appReport.
                getYarnApplicationState().name()));
        }
      }
    } catch (YarnException | IOException ex) {
      logger.error("",ex);
    }
    return reports;
  }
  private Configuration setConfiguration(String hadoopDir)
          throws IllegalStateException {
    //Get the path to the Yarn configuration file from environment variables
    String yarnConfDir = System.getenv(Settings.ENV_KEY_YARN_CONF_DIR);
//      If not found in environment variables: warn and use default,
    if (yarnConfDir == null) {
      yarnConfDir = Settings.getYarnConfDir(hadoopDir);

    }

    org.apache.hadoop.fs.Path confPath = new org.apache.hadoop.fs.Path(yarnConfDir);
    File confFile = new File(confPath + File.separator
            + Settings.DEFAULT_YARN_CONFFILE_NAME);
    if (!confFile.exists()) {
      throw new IllegalStateException("No Yarn conf file");
    }

    //Also add the hadoop config
    String hadoopConfDir = System.getenv(Settings.ENV_KEY_HADOOP_CONF_DIR);
    //If not found in environment variables: warn and use default
    if (hadoopConfDir == null) {
      hadoopConfDir = hadoopDir + "/" + Settings.HADOOP_CONF_RELATIVE_DIR;
    }
    confPath = new org.apache.hadoop.fs.Path(hadoopConfDir);
    File hadoopConf = new File(confPath + "/"
            + Settings.DEFAULT_HADOOP_CONFFILE_NAME);
    if (!hadoopConf.exists()) {
      throw new IllegalStateException("No Hadoop conf file");
    }

    //And the hdfs config
    File hdfsConf = new File(confPath + "/"
            + Settings.DEFAULT_HDFS_CONFFILE_NAME);
    if (!hdfsConf.exists()) {
      throw new IllegalStateException("No HDFS conf file");
    }

    //Set the Configuration object for the returned YarnClient
    Configuration conf = new Configuration();
    conf.addResource(new org.apache.hadoop.fs.Path(confFile.getAbsolutePath()));
    conf.addResource(new org.apache.hadoop.fs.Path(hadoopConf.getAbsolutePath()));
    conf.addResource(new org.apache.hadoop.fs.Path(hdfsConf.getAbsolutePath()));

    YarnRunner.Builder.addPathToConfig(conf, confFile);
    YarnRunner.Builder.addPathToConfig(conf, hadoopConf);
    YarnRunner.Builder.setDefaultConfValues(conf);
    return conf;
  }

}
