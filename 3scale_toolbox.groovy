#!groovy

def importOpenAPI(Map conf) {
  assert conf.destination != null
  assert conf.baseSystemName != null
  assert conf.oasFile != null

  // Read the OpenAPI Specification file
  def openAPI = readOpenAPISpecificationFile(conf.oasFile)
  assert openAPI.swagger == "2.0"
  def version = openAPI.info.version
  assert version != null
  def major = version.tokenize(".")[0]
  def baseName = basename(conf.oasFile)

  // Compute the target system_name
  def targetSystemName = (conf.environmentName != null ? "${conf.environmentName}_" : "") + conf.baseSystemName + "_${major}"

  def commandLine = "3scale import openapi -t ${targetSystemName} -d ${conf.destination} /artifacts/${baseName}"
  def result = runToolbox(commandLine: commandLine, 
                          jobName: "import",
                          openAPI: [
                            "filename": baseName,
                            "content": readFile(conf.oasFile)
                          ],
                          toolboxConfig: conf.toolboxConfig)
  echo result.stdout
}

def basename(path) {
  return path.drop(path.lastIndexOf("/") != -1 ? path.lastIndexOf("/") : 0)
}

def readOpenAPISpecificationFile(fileName) {
  if (fileName.toLowerCase().endsWith(".json")) {
    return readJSON(file: fileName)
  } else if (fileName.toLowerCase().endsWith(".yaml") || fileName.toLowerCase().endsWith(".yml")) {
    return readYaml(file: fileName)
  } else {
    throw new Exception("Can't decide between JSON and YAML on ${fileName}")
  }
}

def getToolboxVersion() {
  def result = runToolbox(commandLine: "3scale -v",
                          jobName: "version")
  return result.stdout
}

def generateRandomBaseSystemName() {
  String alphabet = (('A'..'N')+('P'..'Z')+('a'..'k')+('m'..'z')+('2'..'9')).join() 
  def length = 6
  id = new Random().with {
    (1..length).collect{ alphabet[nextInt(alphabet.length())] }.join()
  }
  return "testcase_${id}"
}

def runToolbox(Map conf) {
  def result = null

  assert conf.jobName != null
  assert conf.commandLine != null

  def defaultToolboxConf = [
    "toolboxConfig": null,
    "openAPI": null,
    "image": "quay.io/redhat/3scale-toolbox:v0.10.0",
    "backoffLimit": 2, // three attempts (one first try + two retries)
    "imagePullPolicy": "IfNotPresent",
    "activeDeadlineSeconds": 90
  ]

  // Apply default values
  conf = defaultToolboxConf + conf

  if (conf.toolboxConfig != null && conf.toolboxConfig.configFileId != null) {
    // Generate a default secret name if none has been provided 
    if (conf.toolboxConfig.secretName == null) {
      conf.toolboxConfig = [
        "configFileId": conf.toolboxConfig.configFileId,
        "secretName": "3scale-toolbox-${JOB_BASE_NAME}"
      ]
    }
    
    echo "Creating a secret named ${conf.toolboxConfig.secretName} containing file ${conf.toolboxConfig.configFileId}..."
    configFileProvider([configFile(fileId: conf.toolboxConfig.configFileId, variable: 'TOOLBOX_CONFIG')]) {
        def toolboxConfig = readFile(TOOLBOX_CONFIG)
        createSecret(conf.toolboxConfig.secretName, [ ".3scalerc.yaml": toolboxConfig ])
      }
  }

  def oasConfigMapName = null
  if (conf.openAPI != null) {
    oasConfigMapName = "3scale-toolbox-${JOB_BASE_NAME}-${BUILD_NUMBER}-openapi"
    echo "Creating a configMap named ${oasConfigMapName} containing the OpenAPI file..."
    createConfigMap(oasConfigMapName, [ (conf.openAPI.filename): conf.openAPI.content ])
  }

  def jobName = "${JOB_BASE_NAME}-${BUILD_NUMBER}-${conf.jobName}"
  def jobSpecs = [
    "apiVersion": "batch/v1",
    "kind": "Job",
    "metadata": [
      "name": jobName,
      "labels": [
        "build": "${JOB_BASE_NAME}-${BUILD_NUMBER}",
        "job": "${JOB_BASE_NAME}"
      ]
    ],
    "spec": [
      "backoffLimit": conf.backoffLimit, 
      "activeDeadlineSeconds": conf.activeDeadlineSeconds,
      "template": [
        "spec": [
          "restartPolicy": "Never",
          "containers": [
            [
              "name": "job",
              "image": conf.image,
              "imagePullPolicy": conf.imagePullPolicy,
              "command": [ "scl", "enable", "rh-ruby25", "/opt/rh/rh-ruby25/root/usr/local/bin/${conf.commandLine}" ],
              "volumeMounts": [
                [
                  "mountPath": "/opt/app-root/src/",
                  "name": "toolbox-config"
                ],
                [
                  "mountPath": "/artifacts",
                  "name": "artifacts"
                ]

              ]
            ]
          ],
          "volumes": [
            [
              "name": "toolbox-config"
            ],
            [
              "name": "artifacts"
            ]
          ]
        ]
      ]
    ]
  ]

  // Inject the toolbox configuration as a volume
  if (conf.toolboxConfig != null && conf.toolboxConfig.secretName != null) {
    jobSpecs.spec.template.spec.volumes[0].secret = [
      "secretName": conf.toolboxConfig.secretName
    ]
  } else {
    jobSpecs.spec.template.spec.volumes[0].emptyDir = [:]
  }

  // Inject the OpenAPI file as a volume
  if (oasConfigMapName != null) {
    jobSpecs.spec.template.spec.volumes[1].configMap = [
      "name": oasConfigMapName
    ]
  } else {
    jobSpecs.spec.template.spec.volumes[1].emptyDir = [:]
  }

  def job = null
  try {
    job = openshift.create(jobSpecs)

    int jobTimeout = 2 + (int)(conf.activeDeadlineSeconds / 60.0f)
    echo "Waiting ${jobTimeout} minutes for the job to complete..."
    timeout(jobTimeout) {
      // Wait for the job to complete, either Succeeded or Failed
      job.watch {
        def jobStatus = getJobStatus(it.object())
        echo "Job ${it.name()}: succeeded = ${jobStatus.succeeded}, failed = ${jobStatus.failed}, status = ${jobStatus.status}, reason = ${jobStatus.reason}"
        
        // Exit the watch loop when the Job has one successful pod or failed
        return jobStatus.succeeded > 0 || jobStatus.status == "Failed"
      }
    }
  } finally {
    if (job != null) {
      def jobStatus = getJobStatus(job.object())
      echo "job ${job.name()} has status '${jobStatus.status}' and reason '${jobStatus.reason}'"

      // Iterate over pods to find:
      //  - the pod that succeeded
      //  - as last resort, a pod that failed 
      def pods = job.related("pod")
      pods.withEach {
        if (it.object().status.phase == "Succeeded") {
          result = getPodDetails(it)
        }
        if (it.object().status.phase == "Failed" && result == null) {
          result = getPodDetails(it)
        }
      }

      if (result != null && result.podPhase == "Failed") {
        echo "RC: ${result.status}"
        echo "STDOUT:"
        echo "-------"
        echo result.stdout
        echo "STDERR:"
        echo "-------"
        echo result.stderr

        error("job ${job.name()} exited with '${jobStatus.status}' and reason '${jobStatus.reason}'")
      }

      // Delete the job
      try {
        openshift.selector('job', jobName).delete()
      } catch (e2) { // Best effort
        echo "cannot delete the job ${jobName}: ${e2}"
      }
    }

    // Delete the temporary configMap containing the OAS file
    if (oasConfigMapName != null) {
      try {
        openshift.selector('configMap', oasConfigMapName).delete()
      } catch (e2) { // Best effort
        echo "cannot delete the configMap ${oasConfigMapName}: ${e2}"
      }
    }

    // Delete the temporary secret
    if (conf.toolboxConfig != null && conf.toolboxConfig.configFileId != null) {
      try {
        openshift.selector('secret', conf.toolboxConfig.secretName).delete()
      } catch (e2) { // Best effort
        echo "cannot delete the secret ${conf.toolboxConfig.secretName}: ${e2}"
      }
    }
  }

  return result
}

def getJobStatus(obj) {
  return [
    "succeeded": obj.status.succeeded != null ? obj.status.succeeded : 0,
    "failed": obj.status.failed != null ? obj.status.failed : 0,
    "status": obj.status.conditions != null && obj.status.conditions.size() > 0 ? obj.status.conditions[0].type : "Unknown",
    "reason": obj.status.conditions != null && obj.status.conditions.size() > 0 ? obj.status.conditions[0].reason : ""
  ]
}
def getPodDetails(pod) {
  def logs = pod.logs()
  return [
    "status": logs.actions[0].status,
    "stdout": logs.actions[0].out,
    "stderr": logs.actions[0].err,
    "podPhase": pod.object().status.phase,
    "podName": pod.name()
  ]
}

def createConfigMap(configMapName, content) {
  def configMapSpecs = [
    "apiVersion": "v1",
    "kind": "ConfigMap",
    "metadata": [
      "name": "${configMapName}",
      "labels": [
        "job": "${JOB_BASE_NAME}",
        "build": "${JOB_BASE_NAME}-${BUILD_NUMBER}"
      ]
    ],
    "data": [:]
  ]
  content.each{ k, v -> configMapSpecs.data[k] = v }
  openshift.apply(configMapSpecs) 
}

def createSecret(secretName, content) {
  def secretSpecs = [
    "apiVersion": "v1",
    "kind": "Secret",
    "metadata": [
      "name": "${secretName}",
      "labels": [
        "job": "${JOB_BASE_NAME}"
      ]
    ],
    "stringData": [:]
  ]
  content.each{ k, v -> secretSpecs.stringData[k] = v }
  openshift.apply(secretSpecs) 
}

// required to be loaded from a jenkins pipeline
return this;
