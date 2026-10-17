#define main supervisor_cli_main
#include "supervisor.c"
#undef main

static int guard_test(void) {
    int ready[2]; if(pipe2(ready,O_CLOEXEC)) fail("test ready pipe");
    if(prctl(PR_SET_CHILD_SUBREAPER,1,0,0,0)) fail("test subreaper");
    pid_t controller=fork(); if(controller<0) fail("test controller fork");
    if(controller==0) {
        close(ready[0]); pid_t owner=getpid();
        pid_t worker=fork(); if(worker<0) fail("test worker fork");
        if(worker==0) {
            if(setgroups(0,NULL)||setgid(65534)||setuid(65534)) fail("test privilege drop");
            parent_guard(owner);
            int sig=0;if(prctl(PR_GET_PDEATHSIG,&sig)||sig!=SIGKILL) fail("test parent guard restored");
            pid_t self=getpid();if(write(ready[1],&self,sizeof(self))!=(ssize_t)sizeof(self)) fail("test ready write");
            for(;;) pause();
        }
        close(ready[1]); for(;;) pause();
    }
    close(ready[1]); pid_t worker=0;
    struct pollfd event={ready[0],POLLIN,0};
    if(poll(&event,1,5000)<=0||read(ready[0],&worker,sizeof(worker))!=(ssize_t)sizeof(worker)) {
        kill(controller,SIGKILL);waitpid(controller,NULL,0);close(ready[0]);return 1;
    }
    close(ready[0]); kill(controller,SIGKILL); waitpid(controller,NULL,0);
    time_t deadline=monotonic_seconds()+3; int status=0;
    do {
        pid_t done=waitpid(worker,&status,WNOHANG);
        if(done==worker) return WIFSIGNALED(status)&&WTERMSIG(status)==SIGKILL?0:1;
        if(done<0&&errno!=EINTR) return 1;
        struct timespec delay={0,20000000};nanosleep(&delay,NULL);
    } while(monotonic_seconds()<deadline);
    kill(worker,SIGKILL);waitpid(worker,NULL,0);return 1;
}
static int state_test(void) {
    char directory[]="/tmp/dsh-supervisor-test-XXXXXX";
    if(!mkdtemp(directory)) fail("test state directory");
    char fifo[PATH_MAX],marker[PATH_MAX];path(fifo,directory,"known-job");path(marker,directory,"known-job.unresolved");
    if(mkfifo(fifo,0600)) fail("test control FIFO");
    int reader=open(fifo,O_RDWR|O_NONBLOCK|O_CLOEXEC);if(reader<0) fail("test control reader");
    char *args[]={"supervisor","control",directory,"known-job","ready",NULL};
    if(supervisor_cli_main(5,args)!=0) return 1;
    if(retain_unresolved(fifo,directory)) fail("test unresolved rename");
    struct stat st;
    if(lstat(fifo,&st)==0||lstat(marker,&st)||!S_ISFIFO(st.st_mode)||state_control(directory,0)!=79) return 1;
    close(reader);unlink(marker);rmdir(directory);return 0;
}
static int apk_owner_test(void) {
    char base[]="/tmp/dsh-apk-owner-test-XXXXXX";
    if(!mkdtemp(base)||chown(base,42424,42424)) fail("test APK base owner");
    char data[PATH_MAX];path(data,base,"data");if(mkdir(data,0755)) fail("test APK data");
    int ready[2];if(pipe2(ready,O_CLOEXEC)) fail("test APK pipe");
    pid_t owner=fork();if(owner<0) fail("test APK fork");
    if(owner==0) {
        close(ready[0]);
        if(setgroups(0,NULL)||setgid(42424)||setuid(42424)) fail("test APK identity");
        char byte='R';if(write(ready[1],&byte,1)!=1) fail("test APK ready");
        close(ready[1]);for(;;) pause();
    }
    close(ready[1]);struct pollfd startup={ready[0],POLLIN,0};char byte=0;
    if(poll(&startup,1,5000)<=0||read(ready[0],&byte,1)!=1) fail("test APK startup");
    close(ready[0]);int owner_fd=owner_pidfd(owner,data);
    pid_t worker=fork();if(worker<0) fail("test APK managed worker");
    if(worker==0) {if(setpgid(0,0)) fail("test worker group");for(;;) pause();}
    if(setpgid(worker,worker)&&errno!=EACCES) fail("test parent worker group");
    kill(owner,SIGKILL);if(waitpid(owner,NULL,0)!=owner) fail("test reap APK owner");
    struct pollfd event={owner_fd,POLLIN,0};
    if(poll(&event,1,3000)<=0) fail("test APK pidfd death event");
    stopping=0;observe_owner(&owner_fd,event.revents);
    if(!stopping||owner_fd!=-1) fail("test APK stop transition");
    int status=0,remaining=reap_managed(worker,&status);
    rmdir(data);rmdir(base);stopping=0;
    return remaining||!WIFSIGNALED(status)||WTERMSIG(status)!=SIGKILL;
}
int main(void) {
    if(geteuid()!=0) return 77;
    if(guard_test()||state_test()||apk_owner_test()) {fprintf(stderr,"Native lifecycle test failed\n");return 1;}
    puts("Native lifecycle checks passed (host UID drop/parent death, readiness, unresolved evidence, fake APK owner pidfd death and bounded cleanup; not Android runtime acceptance).");
    return 0;
}
