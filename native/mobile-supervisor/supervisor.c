#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <grp.h>
#include <linux/capability.h>
#include <limits.h>
#include <poll.h>
#include <sched.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mount.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <sys/utsname.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

static void fail(const char *what) { perror(what); exit(1); }
static time_t monotonic_seconds(void) {
    struct timespec now; if(clock_gettime(CLOCK_MONOTONIC,&now)) fail("monotonic clock");
    return now.tv_sec;
}
static void path(char *out, const char *a, const char *b) {
    if (snprintf(out, PATH_MAX, "%s/%s", a, b) >= PATH_MAX) { errno=ENAMETOOLONG; fail("path"); }
}
static int safe_id(const char *s) {
    if (!*s || strlen(s)>100) return 0;
    for (; *s; ++s) if (!((*s>='a'&&*s<='z')||(*s>='A'&&*s<='Z')||(*s>='0'&&*s<='9')||*s=='-')) return 0;
    return 1;
}
static void require_enforcing(void) {
    int fd=open("/sys/fs/selinux/enforce",O_RDONLY|O_CLOEXEC);
    char mode=0;
    if(fd<0||read(fd,&mode,1)!=1||mode!='1') {
        fprintf(stderr,"SELinux Enforcing required; policy is never disabled by this app\n"); exit(78);
    }
    close(fd);
}
static void require_state(const char *state) {
    struct stat st;
    if(lstat(state,&st)||!S_ISDIR(st.st_mode)||st.st_uid!=0||(st.st_mode&0077)) {
        errno=EPERM; fail("private root controller state");
    }
}
static void parent_guard(pid_t controller) {
    if(prctl(PR_SET_PDEATHSIG,SIGKILL,0,0,0)||getppid()!=controller) {
        errno=ECHILD; fail("controller died during privilege transition");
    }
}
static int owner_pidfd(pid_t owner,const char *data) {
#if defined(SYS_pidfd_open)
    int fd=(int)syscall(SYS_pidfd_open,owner,0);
    if(fd<0) fail("APK owner pidfd unavailable; runtime unsupported");
    char parent[PATH_MAX],status_path[128];path(parent,data,"..");
    struct stat base;
    if(lstat(parent,&base)||!S_ISDIR(base.st_mode)) fail("APK-owned runtime base");
    snprintf(status_path,sizeof(status_path),"/proc/%ld/status",(long)owner);
    FILE *status=fopen(status_path,"r");if(!status) fail("APK owner UID evidence");
    unsigned long real=0,effective=0,saved=0,fs=0;int matched=0;char line[512];
    while(fgets(line,sizeof(line),status)) {
        if(sscanf(line,"Uid: %lu %lu %lu %lu",&real,&effective,&saved,&fs)==4) {
            matched=real==(unsigned long)base.st_uid&&effective==real&&saved==real&&fs==real;
            break;
        }
    }
    fclose(status);
    /* An already-dead pidfd also rejects PID reuse between pidfd_open and proc inspection. */
    struct pollfd event={fd,POLLIN,0};
    if(!matched||poll(&event,1,0)!=0) {errno=EPERM;fail("APK owner identity changed or exited");}
    return fd;
#else
    (void)owner;(void)data;
    errno=ENOSYS;fail("APK owner pidfd unavailable; runtime unsupported");return -1;
#endif
}
static int retain_unresolved(const char *fifo,const char *state) {
    char unresolved[PATH_MAX];
    if(snprintf(unresolved,sizeof(unresolved),"%s.unresolved",fifo)>=(int)sizeof(unresolved)) {errno=ENAMETOOLONG;return -1;}
    if(rename(fifo,unresolved)) return -1;
    int fd=open(state,O_RDONLY|O_DIRECTORY|O_CLOEXEC|O_NOFOLLOW);
    if(fd<0) return -1;
    int result=fsync(fd); int saved=errno; close(fd); errno=saved; return result;
}
static void bind_resolver(const char *root,const char *data) {
    char source[PATH_MAX],target[PATH_MAX],descriptor[64];
    path(source,data,"../resolv.conf"); path(target,root,"etc/resolv.conf");
    int fd=open(source,O_RDONLY|O_CLOEXEC|O_NOFOLLOW);
    if(fd<0) fail("open active-network resolver");
    struct stat source_stat,target_stat;
    if(fstat(fd,&source_stat)||!S_ISREG(source_stat.st_mode)||source_stat.st_size<=0||source_stat.st_size>4096
            ||lstat(target,&target_stat)||!S_ISREG(target_stat.st_mode)) {errno=EINVAL;fail("resolver regular file required");}
    snprintf(descriptor,sizeof(descriptor),"/proc/self/fd/%d",fd);
    if(mount(descriptor,target,NULL,MS_BIND,NULL)
            ||mount(NULL,target,NULL,MS_BIND|MS_REMOUNT|MS_RDONLY|MS_NOSUID|MS_NODEV|MS_NOEXEC,NULL)) fail("bind active-network resolver");
    close(fd);
}
static void bind_dir(const char *source,const char *root,const char *dest) {
    char p[PATH_MAX]; path(p,root,dest);
    struct stat st;
    if (lstat(p,&st)||!S_ISDIR(st.st_mode)) { errno=EINVAL; fail("runtime mountpoint"); }
    if (mount(source,p,NULL,MS_BIND|MS_REC,NULL)) fail("bind data");
    if (mount(NULL,p,NULL,MS_BIND|MS_REMOUNT|MS_NOSUID|MS_NODEV,NULL)) fail("secure data mount");
}
static void child_run(const char *root,const char *data,char **cmd,pid_t controller) {
    if (setpgid(0,0)) fail("setpgid");
    if (unshare(CLONE_NEWNS)) fail("private mount namespace");
    if (mount(NULL,"/",NULL,MS_REC|MS_PRIVATE,NULL)) fail("private mounts");
    if (mount(root,root,NULL,MS_BIND|MS_REC,NULL)) fail("bind root");
    char p[PATH_MAX], src[PATH_MAX];
    path(src,data,"home"); bind_dir(src,root,"home/dsh");
    path(src,data,"tmp"); bind_dir(src,root,"tmp");
    path(p,root,"proc");
    if (mount("proc",p,"proc",MS_NOSUID|MS_NODEV|MS_NOEXEC,NULL)) fail("mount proc");
    /* Only explicit device nodes are exposed, never the host root or shared storage. */
    const char *devices[]={"null","zero","random","urandom","tty"};
    for (unsigned i=0;i<sizeof(devices)/sizeof(devices[0]);i++) {
        char dest[64],source[64]; snprintf(dest,sizeof(dest),"dev/%s",devices[i]);
        snprintf(source,sizeof(source),"/dev/%s",devices[i]); path(p,root,dest);
        if (mount(source,p,NULL,MS_BIND,NULL)) fail("bind device");
    }
    path(p,root,"dev/pts");
    if(mount("devpts",p,"devpts",MS_NOSUID|MS_NOEXEC,"newinstance,ptmxmode=0666,mode=0620,gid=3003")) fail("private devpts");
    path(src,root,"dev/pts/ptmx"); path(p,root,"dev/ptmx");
    if(mount(src,p,NULL,MS_BIND,NULL)) fail("private ptmx");
    bind_resolver(root,data);
    if (mount(NULL,root,NULL,MS_BIND|MS_REMOUNT|MS_RDONLY|MS_NOSUID|MS_NODEV,NULL)) fail("read-only root");
    if (chroot(root)||chdir("/home/dsh")) fail("chroot");
    for (int cap=0;cap<=CAP_LAST_CAP;cap++)
        if (prctl(PR_CAPBSET_DROP,cap,0,0,0) && errno!=EINVAL) fail("drop bounding capabilities");
    gid_t network=3003;
    if (setgroups(1,&network)||setgid(65534)||setuid(65534)) fail("runtime identity");
    struct __user_cap_header_struct h={_LINUX_CAPABILITY_VERSION_3,0};
    struct __user_cap_data_struct caps[2]={{0}};
    if (syscall(SYS_capset,&h,caps)||prctl(PR_SET_NO_NEW_PRIVS,1,0,0,0)) fail("runtime capabilities");
    /* Credential transitions clear PDEATHSIG; restore it only after all UID/GID changes. */
    parent_guard(controller);
    clearenv();
    setenv("HOME","/home/dsh",1); setenv("DSH_HOME","/home/dsh/.dsh",1);
    setenv("PATH","/opt/node/bin:/usr/local/bin:/usr/bin:/bin",1);
    setenv("TERM","xterm-256color",1); setenv("TMPDIR","/tmp",1);
    setenv("DSH_TELEMETRY_DISABLED","1",1);
    execvp(cmd[0],cmd); fail("exec runtime");
}
static volatile sig_atomic_t stopping;
static void observe_owner(int *fd,short events) {
    if(*fd>=0&&(events&(POLLIN|POLLHUP|POLLERR|POLLNVAL))) {
        close(*fd);*fd=-1;stopping=1;
    }
}
static int wakefds[2]={-1,-1};
static void wake_controller(void) {
    int saved=errno;
    if(wakefds[1]>=0) { char value=1; ssize_t ignored=write(wakefds[1],&value,1); (void)ignored; }
    errno=saved;
}
static void signal_stop(int sig) { (void)sig; stopping=1; wake_controller(); }
static void signal_child(int sig) { (void)sig; wake_controller(); }
static void kill_adopted_children(void) {
    char file[128]; snprintf(file,sizeof(file),"/proc/self/task/%ld/children",(long)getpid());
    FILE *children=fopen(file,"r");
    if(!children) return; /* Bounded reaping reports UNKNOWN when discovery is unavailable. */
    long pid;
    while(fscanf(children,"%ld",&pid)==1) {
        if(pid<=1||pid>INT_MAX) continue;
        /* pidfds prevent signalling an unrelated process after PID reuse. */
#if defined(SYS_pidfd_open) && defined(SYS_pidfd_send_signal)
        int fd=(int)syscall(SYS_pidfd_open,(pid_t)pid,0);
        if(fd>=0) {syscall(SYS_pidfd_send_signal,fd,SIGKILL,NULL,0);close(fd);}
#else
        (void)pid; /* No unsafe kill(pid) fallback on headers without pidfd support. */
#endif
    }
    fclose(children);
}
static int reap_managed(pid_t child,int *status) {
    /* The main PID is still unreaped until group signalling completes. */
    kill(-child,SIGCONT); kill(-child,SIGKILL);
    pid_t reaped=waitpid(child,status,WNOHANG);
    if(reaped<0&&errno!=EINTR) fail("reap main child");
    time_t deadline=monotonic_seconds()+3;
    for(;;) {
        kill_adopted_children();
        int adopted_status=0;pid_t adopted=waitpid(-1,&adopted_status,WNOHANG);
        if(adopted>0) {if(adopted==child) *status=adopted_status;continue;}
        if(adopted<0&&errno==ECHILD) return 0;
        if(adopted<0&&errno!=EINTR) fail("reap descendants");
        if(monotonic_seconds()>=deadline) return 1;
        struct timespec delay={0,50000000};nanosleep(&delay,NULL);
    }
}
static int run(int argc,char **argv) {
    if (argc<9||strcmp(argv[7],"--")||!safe_id(argv[5])) return 2;
    char *end=NULL;errno=0;long owner=strtol(argv[6],&end,10);
    if(errno||!end||*end||owner<=1||owner>INT_MAX) return 2;
    require_state(argv[4]);
    int owner_fd=owner_pidfd((pid_t)owner,argv[3]);
    char fifo[PATH_MAX]; path(fifo,argv[4],argv[5]);
    if (mkfifo(fifo,0600)) fail("create job control");
    int fd=open(fifo,O_RDWR|O_NONBLOCK|O_CLOEXEC|O_NOFOLLOW);
    if (fd<0) fail("open job control");
    if(pipe2(wakefds,O_CLOEXEC|O_NONBLOCK)) fail("controller signal pipe");
    struct sigaction action={0}; action.sa_handler=signal_stop;
    sigaction(SIGTERM,&action,NULL); sigaction(SIGINT,&action,NULL);
    action.sa_handler=signal_child; sigaction(SIGCHLD,&action,NULL);
    /* The root controller is outside the managed child's process group. */
    if (prctl(PR_SET_CHILD_SUBREAPER,1,0,0,0)) fail("subreaper");
    /* Do not execute a short-lived command before Java has confirmed its control channel. */
    int admitted=0; time_t admission_deadline=monotonic_seconds()+15;
    while(!admitted&&!stopping) {
        int timeout=(int)(admission_deadline-monotonic_seconds())*1000;
        if(timeout<=0) break;
        struct pollfd events[3]={{fd,POLLIN,0},{wakefds[0],POLLIN,0},{owner_fd,POLLIN,0}};
        int ready=poll(events,3,timeout);
        if(ready<0&&errno!=EINTR) fail("launch admission poll");
        if(ready>0) observe_owner(&owner_fd,events[2].revents);
        if(ready>0&&(events[1].revents&POLLIN)) {char bytes[64];while(read(wakefds[0],bytes,sizeof(bytes))>0) {}}
        if(ready>0&&(events[0].revents&POLLIN)) {
            char bytes[64];ssize_t count=read(fd,bytes,sizeof(bytes));
            for(ssize_t i=0;i<count;i++) {if(bytes[i]=='R') admitted=1;else if(bytes[i]=='S') stopping=1;}
        }
    }
    if(!admitted||stopping) {
        if(owner_fd>=0) close(owner_fd);
        close(fd);close(wakefds[0]);close(wakefds[1]);
        if(unlink(fifo)) {perror("remove unstarted controller state");return 79;}
        fprintf(stderr,"Launch cancelled or readiness handshake timed out before creating a worker\n");return stopping?143:78;
    }
    pid_t controller=getpid();
    pid_t child=fork(); if (child<0) fail("fork");
    if (!child) {
        if(owner_fd>=0) close(owner_fd);
        close(fd); close(wakefds[0]); close(wakefds[1]);
        signal(SIGTERM,SIG_DFL); signal(SIGINT,SIG_DFL); signal(SIGCHLD,SIG_DFL);
        parent_guard(controller);
        child_run(argv[2],argv[3],&argv[8],controller);
    }
    if(setpgid(child,child)&&errno!=EACCES&&errno!=ESRCH) fail("managed process group");
    int status=0,done=0; time_t deadline=0;
    while (!done) {
        siginfo_t info={0};
        if(waitid(P_PID,(id_t)child,&info,WEXITED|WNOHANG|WNOWAIT)&&errno!=EINTR) fail("inspect child");
        if(info.si_pid==child) { done=1; break; }
        if (stopping&&!deadline) { kill(-child,SIGCONT); kill(-child,SIGTERM); deadline=monotonic_seconds()+5; }
        if (deadline&&monotonic_seconds()>=deadline) kill(-child,SIGKILL);
        if (deadline&&monotonic_seconds()>=deadline+2) break;
        struct pollfd event[3]={{fd,POLLIN,0},{wakefds[0],POLLIN,0},{owner_fd,POLLIN,0}};
        int ready=poll(event,3,deadline?100:-1);
        if (ready<0&&errno!=EINTR) fail("control poll");
        if(ready>0) observe_owner(&owner_fd,event[2].revents);
        if (ready>0&&(event[1].revents&POLLIN)) {char bytes[64]; while(read(wakefds[0],bytes,sizeof(bytes))>0) {}}
        if (ready>0&&(event[0].revents&POLLIN)) { char bytes[64]; ssize_t n=read(fd,bytes,sizeof(bytes));
            for (ssize_t i=0;i<n;i++) {
                if(bytes[i]=='S') stopping=1;
                else if(bytes[i]=='F') kill(-child,SIGSTOP);
                else if(bytes[i]=='T') kill(-child,SIGCONT);
            }
        }
    }
    if(owner_fd>=0) close(owner_fd);
    int remaining=reap_managed(child,&status);
    if(remaining) {
        /* Rename preserves controller evidence atomically, including on a full filesystem. */
        if(retain_unresolved(fifo,argv[4])) perror("retain unresolved controller FIFO");
        close(fd); close(wakefds[0]); close(wakefds[1]);
        fprintf(stderr,"Unreaped descendants remain: escaped groups are not contained; cleanup UNKNOWN\n");return 79;
    }
    close(fd); close(wakefds[0]); close(wakefds[1]);
    if(unlink(fifo)) {perror("remove confirmed controller state");return 79;}
    return WIFEXITED(status)?WEXITSTATUS(status):128+WTERMSIG(status);
}
static void ownership(const char *p,uid_t uid,gid_t gid) {
    struct stat st; if(lstat(p,&st)) fail("inspect installation");
    if(!S_ISDIR(st.st_mode)&&!S_ISREG(st.st_mode)) { errno=EINVAL; fail("installation contains link or device"); }
    if(S_ISDIR(st.st_mode)) {
        DIR *dir=opendir(p); if(!dir) fail("open installation directory");
        struct dirent *entry;
        while((entry=readdir(dir))) {
            if(!strcmp(entry->d_name,".")||!strcmp(entry->d_name,"..")) continue;
            char child[PATH_MAX]; path(child,p,entry->d_name); ownership(child,uid,gid);
        }
        closedir(dir);
    }
    if(chown(p,uid,gid)||chmod(p,S_ISDIR(st.st_mode)?0755:((st.st_mode&0111)?0755:0644))) fail("installation permissions");
}
static void directory(const char *p) {
    if(mkdir(p,0755)&&errno!=EEXIST) fail("create data directory");
    struct stat st; if(lstat(p,&st)||!S_ISDIR(st.st_mode)) {errno=EINVAL;fail("data directory");}
}
static int install_root(const char *staging,const char *base) {
    char root[PATH_MAX],data[PATH_MAX],p[PATH_MAX]; path(root,base,"rootfs");
    struct stat st; if(!lstat(root,&st)||errno!=ENOENT) {errno=EEXIST;fail("runtime already installed; stop and remove explicitly");}
    ownership(staging,0,0);
    path(data,base,"data"); directory(data);
    path(p,data,"home"); directory(p); ownership(p,65534,65534);
    path(p,data,"tmp"); directory(p); ownership(p,65534,65534);
    path(p,base,"state"); directory(p); if(chown(p,0,0)||chmod(p,0700)) fail("state permissions");
    /* The only visible install step is an atomic rename on the app's filesystem. */
    if(rename(staging,root)) fail("commit runtime");
    int fd=open(base,O_RDONLY|O_DIRECTORY|O_CLOEXEC); if(fd<0||fsync(fd)) fail("sync runtime installation");
    close(fd); return 0;
}
static int state_control(const char *state,int stop_all) {
    require_state(state);
    DIR *dir=opendir(state); if(!dir) fail("open runtime state");
    int found=0,unknown=0; struct dirent *entry;
    while((entry=readdir(dir))) {
        if(entry->d_name[0]=='.') continue;
        found=1;
        if(!stop_all) continue;
        char p[PATH_MAX]; path(p,state,entry->d_name);
        if(!safe_id(entry->d_name)) {unknown=1;continue;}
        int fd=open(p,O_WRONLY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC);
        if(fd<0) {unknown=1;continue;}
        struct stat st;
        if(fstat(fd,&st)||!S_ISFIFO(st.st_mode)) {close(fd);unknown=1;continue;}
        char stop='S'; if(write(fd,&stop,1)!=1) unknown=1;
        close(fd);
    }
    closedir(dir);
    if(!stop_all&&found) {
        fprintf(stderr,"Runtime controller state remains from a previous owner; stop/recovery required before launching\n");return 79;
    }
    if(stop_all&&found) {
        time_t deadline=monotonic_seconds()+13;
        do {
            int pending=0; dir=opendir(state); if(!dir) fail("recheck runtime state");
            while((entry=readdir(dir))) if(entry->d_name[0]!='.') pending=1;
            closedir(dir);
            if(!pending&&!unknown) return 0;
            if(unknown) break;
            struct timespec delay={0,50000000}; nanosleep(&delay,NULL);
        } while(monotonic_seconds()<deadline);
        fprintf(stderr,"Stopped state could not be confirmed; stale controller or unresolved descendants require manual recovery\n");return 79;
    }
    return 0;
}
int main(int argc,char **argv) {
    if (geteuid()!=0) { fprintf(stderr,"Root authorization required\n"); return 77; }
    if (argc==2&&!strcmp(argv[1],"probe")) {
        require_enforcing();
        struct utsname kernel; if(uname(&kernel)) fail("kernel evidence");
        printf("uid=0\nSELinux=Enforcing\npageSize=%ld\nkernel=%s %s\nfreezer=signal-process-group-fallback\nidentity=65534:65534 groups=3003\n",sysconf(_SC_PAGESIZE),kernel.release,kernel.machine);
        const char *evidence[]={"/proc/self/attr/current","/proc/self/status"};
        for(unsigned i=0;i<sizeof(evidence)/sizeof(evidence[0]);i++) {
            FILE *file=fopen(evidence[i],"r"); char line[512];
            if(!file) {printf("evidence unavailable: %s\n",evidence[i]);continue;}
            while(fgets(line,sizeof(line),file))
                if(i==0||!strncmp(line,"Cap",3)||!strncmp(line,"NoNewPrivs:",11)||!strncmp(line,"Groups:",7)) printf("%s",line);
            fclose(file); putchar('\n');
        }
        printf("KernelSU module path=%s (path evidence only; profile not verified)\n",access("/sys/module/kernelsu",F_OK)==0?"present":"not visible");
        printf("cgroup v2 controllers=%s; dedicated freezer creation NOT TESTED\n",access("/sys/fs/cgroup/cgroup.controllers",R_OK)==0?"visible":"not visible");
        fflush(stdout);
        pid_t probe=fork(); if(probe<0) fail("probe fork");
        if(probe==0) {
            if(unshare(CLONE_NEWNS)) fail("probe private mount namespace");
            if(mount(NULL,"/",NULL,MS_PRIVATE|MS_REC,NULL)) fail("probe mount capability");
            if(chroot("/")||chdir("/")) fail("probe chroot capability");
            _exit(0);
        }
        int status=0; if(waitpid(probe,&status,0)!=probe) fail("probe wait");
        if(!WIFEXITED(status)||WEXITSTATUS(status)!=0) return 78;
        printf("private mount namespace=passed\nmount propagation isolation=passed\nchroot syscall=passed (not runtime launch)\nRuntime DNS/TLS, native addons, UID/profile isolation and freezer remain UNVERIFIED\n");
        return 0;
    }
    if (argc>1&&!strcmp(argv[1],"run")) {
        if(argc<9) return 2;
        require_enforcing(); return run(argc,argv);
    }
    if (argc==4&&!strcmp(argv[1],"install")) {require_enforcing(); return install_root(argv[2],argv[3]);}
    if(argc==3&&!strcmp(argv[1],"check-state")) return state_control(argv[2],0);
    if(argc==3&&!strcmp(argv[1],"stop-all")) return state_control(argv[2],1);
    if (argc==5&&!strcmp(argv[1],"control")&&safe_id(argv[3])) {
        require_state(argv[2]);
        char p[PATH_MAX]; path(p,argv[2],argv[3]);
        int fd=-1; time_t deadline=monotonic_seconds()+3;
        do {
            fd=open(p,O_WRONLY|O_NONBLOCK|O_NOFOLLOW|O_CLOEXEC);
            if(fd>=0||strcmp(argv[4],"ready")||(errno!=ENOENT&&errno!=ENXIO)) break;
            struct timespec delay={0,20000000}; nanosleep(&delay,NULL);
        } while(monotonic_seconds()<deadline);
        if(fd<0) fail("open controller");
        struct stat st;
        if(fstat(fd,&st)||!S_ISFIFO(st.st_mode)||st.st_uid!=0||(st.st_mode&0077)) {errno=EPERM;fail("invalid controller FIFO");}
        if(!strcmp(argv[4],"ready")) {
            char ready='R';if(write(fd,&ready,1)!=1) fail("admit controlled launch");
            close(fd);return 0;
        }
        char op=!strcmp(argv[4],"stop")?'S':!strcmp(argv[4],"freeze")?'F':!strcmp(argv[4],"thaw")?'T':0;
        if(!op) return 2;
        if(write(fd,&op,1)!=1) fail("send control");
        close(fd); return 0;
    }
    fprintf(stderr,"Invalid supervisor arguments\n"); return 2;
}
