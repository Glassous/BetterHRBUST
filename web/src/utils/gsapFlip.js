import { gsap } from 'gsap';
import { Flip } from 'gsap/Flip';

// Flip 插件全局注册一次即可（Web 与油猴脚本共用同一模块图）
gsap.registerPlugin(Flip);

export { gsap, Flip };
