import hudson.*
import hudson.model.*
import jenkins.*
import jenkins.model.*
import java.text.SimpleDateFormat
import java.io.File
import hudson.util.IOUtils

class JobInProgress {
	def job;
	def future;
	def build;
	String exdir;
	boolean unpacked = false;
	boolean packed = false;

	JobInProgress(j, f, e) {job = j; future = f; exdir = e;}
}

class Container {
	private build = Thread.currentThread().executable;
	private resolver = build.buildVariableResolver;
	private out;
	private boolean CREATE_INSTALLERS = (resolver.resolve("Create installers") == "true");

	Container(o) { out = o; }

	class Repo {
		String name;
		String url;
		String branch;
		String hash;

		Repo(String n) {
			name = n;
			url = resolver.resolve(name+'_REPO');
			branch = resolver.resolve(name+'_BRANCH');
			hash = resolver.resolve(name+'_HASH');
			// Note: HASH must be inside BRANCH for the duration of the build, or checkout will fail.
			// Therefore, doing a git push --force during a build is likely to cause that build to fail.
			// At least it won't build the wrong code. :}
		}
	}

	private repos = [
		new Repo('MOSYNC'),
		new Repo('BINUTILS'),
		new Repo('GCC'),
		//new Repo('ECLIPSE'),
	]

	private buildParams = [
		new StringParameterValue('ECLIPSE_VERSION', '3.8'),
		new StringParameterValue('TIMESTAMP', buildId()),
		new StringParameterValue('BUILD_MODE', resolver.resolve('BUILD_MODE')),
		new StringParameterValue('CLEAN_WORKSPACE', resolver.resolve('Clean workspace')),
	];

	def newFile(String dir) {
		return new File(build.getWorkspace().toString() + '/' + dir);
	}

	def start(name, String exdir = null) {
		out.println "Scheduling job ${name}...";
		def job = Hudson.instance.getJob(name);
		def future = job.scheduleBuild2(0, new Cause.UpstreamCause(build), new ParametersAction(buildParams));
		if(future == null) {
			out.println "Could not schedule job ${name}.";
			exit(1);
		}
		out.println "Job scheduled."
		return new JobInProgress(job, future, exdir);
	}

	def handleBuild(jib) {
		def b = jib.build;
		if(b.result == Result.SUCCESS)
		{
			out.println "${b.getAbsoluteUrl()} was successful."
			def artifacts = b.getArtifacts();
			out.println "${artifacts.size()} artifacts."
			for(a in artifacts) {
				def cmd = "unzip -n -q ${a.getFile().getPath()}"
				if(jib.exdir != null) {
					IOUtils.mkdirs(newFile(jib.exdir));
					cmd += " -d ${jib.exdir}"
				}
				exec(cmd, '.');
			}
		}
		else
		{
			out.println "${b.getAbsoluteUrl()} failed (${b.result})."
			build.result = Result.FAILURE
			exit(1)
		}
	}

	def wait(jobInProgress) {
		out.println "Waiting for the completion of ${jobInProgress.job.displayName}...";
		if(jobInProgress.future == null) {
			out.println "Did not (?!?) start job ${name}.";
			exit(1);
		}
		def b = jobInProgress.future.get();
		handleBuild(b);
		return b;
	}

	def exec(String cmd, String dir) {
		def f = newFile(dir);
		out.println "${f}: ${cmd}";
		def p = cmd.execute(null, f);
		p.waitForProcessOutput(out, out);
		int res = p.exitValue();
		if(res == 0)
			return;
		out.println "Command failed: ${res}";
		exit(res);
	}

	def doJobs() {
		exec("ls -al", '.');
		// set up buildParams
		repos.each {
			if(it.hash.length() == 0) {
				def cmd = "git ls-remote ${it.url} ${it.branch}"
				out.println cmd
				it.hash = cmd.execute().text.find(~/\w*/);
			}
			out.println "${it.url} ${it.branch} ${it.hash}";

			buildParams += [
				new StringParameterValue(it.name+'_REPO', it.url),
				new StringParameterValue(it.name+'_BRANCH', it.branch),
				new StringParameterValue(it.name+'_HASH', it.hash),
			]
		}

		def platforms = [
			'Windows',
			'Mac',
		]
		if(!CREATE_INSTALLERS) {
			platforms += 'Linux'
		}

		def platformJobs = platforms.collect { start('Build_GCC4_MoSync_'+it, it) }

		def runtimeJobs = [
			start('Build_GCC4_iOS_Runtime'),
			start('Build_GCC4_WP7_Runtime'),
			start('Build_GCC4_xp_Runtimes'),
			start('Build_GCC4_Linux_Runtimes'),
			//start('Prepare_source_tarball'),
		]

		def allJobs = runtimeJobs + platformJobs;

		allJobs.each {
			out.println "Waiting on start of job ${it.job.displayName}...";
			it.build = it.future.waitForStart();
			out.println "Job ${it.job.displayName} started.";
		}

		if(CREATE_INSTALLERS) {
			while(true) {
				def packagingJobs = [];
				boolean jobsDone = false;
				while(!jobsDone) {
					jobsDone = true;
					allJobs.each {
						if(!it.unpacked) {
							if(!it.build.isBuilding()) {
								handleBuild(it);
								it.unpacked = true;
								buildParams += [
									new StringParameterValue('BUILD_SELECTOR_' + it.job.getName(),
										'<SpecificBuildSelector><buildNumber>' + it.build.number +'</buildNumber></SpecificBuildSelector>'),
								]
							}
							else
							{
								jobsDone = false;
							}
						}
					}
					if(jobsDone) {
						packagingJobs = platforms.collect { start('Package_MoSync_For_'+it+'_GCC4'); }
					}
				}
			}
			// wait one second.
			Thread.sleep(1000);

			boolean packagingDone = false;
			while(!packagingDone) {
				packagingDone = true;
				packagingJobs.each {
					if(it.build.isBuilding()) {
						packagingDone = false;
					}
				}
				// wait one second.
				Thread.sleep(1000);
			}
		} else {	//!CREATE_INSTALLERS
			while(true) {
				// check all jobs, unpack if they're done.
				allJobs.each {
					if(!it.unpacked && !it.build.isBuilding()) {
						handleBuild(it);
						it.unpacked = true;
					}
				}
				boolean runtimesDone = true;
				runtimeJobs.each {
					if(!it.unpacked)
						runtimesDone = false;
				}
				if(runtimesDone) {
					boolean allDone = true;
					platformJobs.each {
						if(it.unpacked && !it.packed) {
							def platform = it.exdir;
							def cmd = "zip -9 -q -r mosyncSDK-${platform}-${buildId()}.zip MoSync"
							exec(cmd, '.');

							cmd = "zip -9 -q -r ../mosyncSDK-${platform}-${buildId()}.zip MoSync"
							exec(cmd, "./${platform}");
						} else {
							allDone = false;
						}
					}
					if(allDone)
						return;
				}
			}
			// wait one second.
			Thread.sleep(1000);
		}
	}

	String buildId() {
		return ((new SimpleDateFormat("yyMMdd-HHmm")).format(build.getTime())) + "-" + build.number;
	}
}

def c = new Container(out);
c.doJobs();
