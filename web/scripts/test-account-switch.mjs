import { semesterOn, teachingWeekOn } from '../src/utils/schoolCalendar.js';
import { normalizeCustomCourse, normalizeTimetableReschedule } from '../src/utils/timetableOverrides.js';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import { ref, reactive, computed } from 'vue';
const A='2401234567', B='2407654321';
const ids={ [A]:'101', [B]:'202' };
const store=new Map([['saved_student_number',B],['better_hrbust_has_session','true'],
 ['better_hrbust_cached_profile',JSON.stringify({studentNumber:A,internalId:ids[A],realName:'测试甲'})],
 ['better_hrbust_cache_scores',JSON.stringify([{courseName:'甲课程'}])]]);
let server=A, wrongIdentity=false, hold=null;
const cleaners=[];
const profile = owner => ({studentNumber:owner,realName:owner===A?'测试甲':'测试乙'});
const api={
 login:async username=>{server=wrongIdentity?A:username;return {success:true};},
 logout:async()=>{server='';},
 getStudentContext:async()=>({studentId:ids[server],year:'2026',term:'1',courses:[]}),
 getPersonalInfo:async()=>profile(server),
 getScores:()=>{const owner=server;if(hold){const {promise,resolve}=Promise.withResolvers();hold.resolve=resolve;return promise;}return Promise.resolve({scores:[{courseName:owner+'课程'}]});},
 getCurriculumPlan:async()=>({groups:[]}),getExams:async()=>[],
 getCalendarInfo:async()=>({notices:[]}),getTimetable:async()=>({cells:[],unarranged:[]})
};
const env={ref,reactive,computed,academicApi:api,Date,console,
 storageGetItem:key=>store.get(key)||null,storageSetItem:(key,value)=>store.set(key,String(value)),storageRemoveItem:key=>store.delete(key),
 saveCredential:(username,password)=>store.set('better_hrbust_saved_credential',JSON.stringify({username,password})),
 clearCredential:()=>store.delete('better_hrbust_saved_credential'),
 normalizeCustomCourse,normalizeTimetableReschedule,
 registerDataCacheCleaner:fn=>cleaners.push(fn),clearRegisteredDataCaches:()=>cleaners.forEach(fn=>fn()),registerCourseColors:()=>{}};
function load(file, additions={}) {
 const source=readFileSync(new URL('../src/composables/'+file,import.meta.url),'utf8').replace(/^import[\s\S]*?;\r?\n/gm,'').replace(/^export /gm,'');
 const context=vm.createContext({ semesterOn, teachingWeekOn,...env,...additions});vm.runInContext(source,context);return context;
}
const session=load('useSession.js').useSession();
const data=load('useAcademicData.js',{useSession:()=>session}).useAcademicData();
assert.equal(session.studentNumber.value,A,'记住的输入学号不能覆盖已验证档案身份');
assert.equal(data.scores.value[0].courseName,'甲课程');
await session.prepareLoginAccount(B);
assert.equal(data.scores.value.length,0);assert.equal(session.userProfile.realName,'');
assert.equal((await session.login({username:B,password:'test',captcha:'0000'})).success,true);
await data.syncAll();
data.upsertCustomCourse({courseName:'物理实验',week:5,day:2,timeMode:'custom',startTime:'14:00',endTime:'16:00'});
assert.equal(data.timetableCustomCourses.value.length,1);
await data.syncAll();
assert.equal(data.timetableCustomCourses.value.length,1,'全量同步不得覆盖本地自定义实验课');
assert.equal(session.userProfile.realName,'测试乙');assert.equal(data.scores.value[0].courseName,B+'课程');
await session.prepareLoginAccount(A);
assert.equal(data.timetableCustomCourses.value.length,0,'切换账号后应清理上一个账号的自定义课程');
assert.equal((await session.login({username:A,password:'test',captcha:'0000'})).success,true);
await data.syncAll();assert.equal(data.scores.value[0].courseName,A+'课程');
hold={};const stale=data.syncAll();
while(!hold.resolve)await new Promise(resolve=>setTimeout(resolve,0));
const staleResolve=hold.resolve;hold=null;
await session.prepareLoginAccount(B);
assert.equal((await session.login({username:B,password:'test',captcha:'0000'})).success,true);
await data.syncAll();
staleResolve({scores:[{courseName:'迟到的甲课程'}]});await stale;
assert.equal(data.scores.value[0].courseName,B+'课程','旧请求不能覆盖新账号');
const originalLogin = api.login;
api.login = async()=>({success:false,message:'学号或密码错误'});
assert.equal((await session.login({username:B,password:'wrong',captcha:'0000'})).success,false);
assert.equal(JSON.parse(store.get('better_hrbust_saved_credential')||'{}').username,B,'登录失败不能覆盖已保存的账号密码');
assert.equal(store.get('better_hrbust_has_session'),undefined);
assert.equal(session.offlineMode.value,true,'失败重认证不会持久化为已登录');
api.login = originalLogin;
assert.equal((await session.login({username:B,password:'test',captcha:'0000'})).success,true);
server=A;
assert.equal(await session.checkAuth({light:true}),false,'外部会话切换不能拼接旧档案和新内部ID');
assert.equal(data.scores.value.length,0);
await session.logout();wrongIdentity=true;
const mismatch=await session.login({username:B,password:'test',captcha:'0000'});
assert.equal(mismatch.success,false);assert.match(mismatch.message,/账号.*不一致/);
assert.equal(session.isLoggedIn.value,false);assert.equal(session.userProfile.realName,'');
wrongIdentity=false;
const originalLogout=api.logout;
let posted=0;const loginFn=api.login;api.login=async(...args)=>{posted++;return loginFn(...args);};
api.logout=async()=>{throw new Error('退出失败');};
await assert.rejects(session.logout(),/退出失败/);
const blocked=await session.login({username:B,password:'test',captcha:'0000'});
assert.equal(blocked.success,false);assert.equal(posted,0,'退出失败不能继续提交新凭证');
api.logout=originalLogout;await session.prepareLoginAccount(B);
assert.equal((await session.login({username:B,password:'test',captcha:'0000'})).success,true);
assert.equal(JSON.parse(store.get('better_hrbust_saved_credential')||'{}').username,B,'登录成功后应保存账号密码供下次自动填入');
// syncAll 在途期间并发判定会话失效（复现 M-1：早退不复位 syncing 导致刷新永久卡死）
hold={};const inflight=data.syncAll();
while(!hold.resolve)await new Promise(resolve=>setTimeout(resolve,0));
const inflightResolve=hold.resolve;hold=null;
session.markSessionExpired();
inflightResolve({scores:[{courseName:'迟到B课程'}]});
const inflightResult=await inflight;
assert.equal(inflightResult.success,false,'会话失效后旧同步结果应被丢弃');
assert.equal(data.syncing.value,false,'并发会话失效的早退路径必须复位 syncing');
const refreshBlocked=await data.refreshAll();
assert.equal(refreshBlocked.expired,true,'失效后刷新应要求重新登录');
assert.equal(data.syncing.value,false,'刷新入口不残留 syncing 状态');
assert.equal((await session.login({username:B,password:'test',captcha:'0000',remember:false})).success,true);
assert.equal(store.has('better_hrbust_saved_credential'),false,'取消"记住密码"后应清除本地已保存的密码');
console.log('通过：A→B→A、缓存隔离、旧同步丢弃、错误身份拒绝、退出失败阻止新凭证提交、同步在途会话失效复位及记住密码凭证的保存/清除');
