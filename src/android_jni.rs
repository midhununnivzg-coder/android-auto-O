//! JNI host adapter for the Android tablet application.
use crate::{
    AndroidAutoAudioInputTrait, AndroidAutoAudioOutputTrait, AndroidAutoConfiguration,
    AndroidAutoInputChannelTrait, AndroidAutoMainTrait, AndroidAutoMessage,
    AndroidAutoSensorTrait, AndroidAutoVideoChannelTrait, AudioChannelType,
    HeadUnitInfo, InputConfiguration, SensorInformation, VideoConfiguration, Wifi,
    SendableAndroidAutoMessage,
};
use jni::{
    objects::{GlobalRef, JClass, JObject, JValue},
    sys::{jint, jlong},
    JNIEnv, JavaVM,
};
use std::{
    collections::{HashMap, HashSet},
    os::fd::{FromRawFd, RawFd},
    sync::{
        atomic::{AtomicI64, Ordering},
        Mutex, OnceLock,
    },
    time::{SystemTime, UNIX_EPOCH},
};
use tokio::sync::{mpsc, oneshot};

struct AndroidHost {
    vm: JavaVM,
    activity: GlobalRef,
    receiver: Mutex<Option<mpsc::Receiver<SendableAndroidAutoMessage>>>,
    sender: mpsc::Sender<SendableAndroidAutoMessage>,
    input: InputConfiguration,
    video: VideoConfiguration,
    sensors: SensorInformation,
}

impl AndroidHost {
    fn callback(&self, name: &str, signature: &str, args: &[JValue]) -> Option<jni::objects::JValueOwned<'_>> {
        let mut env = self.vm.attach_current_thread().ok()?;
        match env.call_method(self.activity.as_obj(), name, signature, args) {
            Ok(value) => Some(value),
            Err(error) => {
                log::error!("Java callback {name} failed: {error}");
                None
            }
        }
    }

    fn status(&self, message: &str) {
        if let Ok(mut env) = self.vm.attach_current_thread() {
            if let Ok(value) = env.new_string(message) {
                let object = JObject::from(value);
                let _ = env.call_method(
                    self.activity.as_obj(),
                    "onNativeStatus",
                    "(Ljava/lang/String;)V",
                    &[JValue::Object(&object)],
                );
            }
        }
    }
}

#[async_trait::async_trait]
impl AndroidAutoSensorTrait for AndroidHost {
    fn get_supported_sensors(&self) -> &SensorInformation { &self.sensors }
    async fn start_sensor(&self, _stype: Wifi::sensor_type::Enum) -> Result<(), ()> { Err(()) }
}

#[async_trait::async_trait]
impl AndroidAutoAudioOutputTrait for AndroidHost {
    async fn open_output_channel(&self, _t: AudioChannelType) -> Result<(), ()> { Ok(()) }
    async fn close_output_channel(&self, _t: AudioChannelType) -> Result<(), ()> { Ok(()) }
    async fn receive_output_audio(&self, t: AudioChannelType, data: Vec<u8>) {
        if let Ok(mut env) = self.vm.attach_current_thread() {
            if let Ok(array) = env.byte_array_from_slice(&data) {
                let object = JObject::from(array);
                let channel = match t {
                    AudioChannelType::Media => 0,
                    AudioChannelType::System => 1,
                    AudioChannelType::Speech => 2,
                };
                let _ = env.call_method(
                    self.activity.as_obj(), "onNativeAudio", "(I[B)V",
                    &[JValue::Int(channel), JValue::Object(&object)],
                );
            }
        }
    }
    async fn start_output_audio(&self, _t: AudioChannelType) {}
    async fn stop_output_audio(&self, _t: AudioChannelType) {}
}

#[async_trait::async_trait]
impl AndroidAutoAudioInputTrait for AndroidHost {
    async fn open_input_channel(&self) -> Result<(), ()> { Ok(()) }
    async fn close_input_channel(&self) -> Result<(), ()> { Ok(()) }
    async fn start_input_audio(&self) {}
    async fn stop_input_audio(&self) {}
    async fn audio_input_ack(&self, _chan: u8, _ack: crate::protobufmod::Wifi::AVMediaAckIndication) {}
}

#[async_trait::async_trait]
impl AndroidAutoInputChannelTrait for AndroidHost {
    async fn binding_request(&self, _code: u32) -> Result<(), ()> { Ok(()) }
    fn retrieve_input_configuration(&self) -> &InputConfiguration { &self.input }
}

#[async_trait::async_trait]
impl AndroidAutoVideoChannelTrait for AndroidHost {
    async fn receive_video(&self, data: Vec<u8>, timestamp: Option<u64>) {
        if let Ok(mut env) = self.vm.attach_current_thread() {
            if let Ok(array) = env.byte_array_from_slice(&data) {
                let object = JObject::from(array);
                let ts = timestamp.unwrap_or(0) as i64;
                let _ = env.call_method(
                    self.activity.as_obj(), "onNativeVideo", "([BJ)V",
                    &[JValue::Object(&object), JValue::Long(ts)],
                );
            }
        }
    }
    async fn setup_video(&self) -> Result<(), ()> {
        let value = self.callback("onNativeVideoSetup", "()Z", &[]).ok_or(())?;
        if value.z().unwrap_or(false) { Ok(()) } else { Err(()) }
    }
    async fn teardown_video(&self) {
        let _ = self.callback("onNativeVideoTeardown", "()V", &[]);
    }
    async fn wait_for_focus(&self) {}
    async fn set_focus(&self, focus: bool) {
        let _ = self.callback("onNativeFocus", "(Z)V", &[JValue::Bool(focus.into())]);
    }
    fn retrieve_video_configuration(&self) -> &VideoConfiguration { &self.video }
}

#[async_trait::async_trait]
impl AndroidAutoMainTrait for AndroidHost {
    async fn connect(&self) { self.status("Android Auto protocol session connected"); }
    async fn disconnect(&self) { self.status("Android Auto protocol session ended"); }
    async fn get_receiver(&self) -> Option<mpsc::Receiver<SendableAndroidAutoMessage>> {
        self.receiver.lock().ok()?.take()
    }
}

struct NativeHandle {
    input: mpsc::Sender<SendableAndroidAutoMessage>,
    stop: Option<oneshot::Sender<()>>,
}
static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);
static HANDLES: OnceLock<Mutex<HashMap<i64, NativeHandle>>> = OnceLock::new();
fn handles() -> &'static Mutex<HashMap<i64, NativeHandle>> {
    HANDLES.get_or_init(|| Mutex::new(HashMap::new()))
}

fn configuration() -> AndroidAutoConfiguration {
    AndroidAutoConfiguration {
        unit: HeadUnitInfo {
            name: "OLA Play".into(),
            car_model: "Generic".into(),
            car_year: "2026".into(),
            car_serial: "OLA-PLAY".into(),
            left_hand: false,
            head_manufacturer: "Midhun Sankar".into(),
            head_model: "OLA Play".into(),
            sw_build: "OLA-PLAY-ANDROID10".into(),
            sw_version: "0.3.8".into(),
            native_media: false,
            hide_clock: Some(false),
        },
        custom_certificate: None,
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_androidauto_headunit_NativeProjection_nativeStart(
    mut env: JNIEnv,
    _class: JClass,
    activity: JObject,
    descriptor: JObject,
    width: jint,
    height: jint,
) -> jlong {
    let result = (|| -> Result<i64, String> {
        let fd = env.call_method(&descriptor, "getFd", "()I", &[])
            .map_err(|e| e.to_string())?.i().map_err(|e| e.to_string())?;
        let duplicated: RawFd = unsafe { libc::dup(fd) };
        if duplicated < 0 { return Err("Could not duplicate TCP socket descriptor".into()); }
        let std_stream = unsafe { std::net::TcpStream::from_raw_fd(duplicated) };
        std_stream.set_nonblocking(true).map_err(|e| e.to_string())?;
        let stream_vm = env.get_java_vm().map_err(|e| e.to_string())?;
        let callback = env.new_global_ref(activity).map_err(|e| e.to_string())?;
        let (input_tx, input_rx) = mpsc::channel(128);
        let (stop_tx, stop_rx) = oneshot::channel();
        let id = NEXT_HANDLE.fetch_add(1, Ordering::Relaxed);
        handles().lock().map_err(|e| e.to_string())?.insert(id, NativeHandle {
            input: input_tx.clone(), stop: Some(stop_tx),
        });
        let host = AndroidHost {
            vm: stream_vm,
            activity: callback,
            receiver: Mutex::new(Some(input_rx)),
            sender: input_tx,
            input: InputConfiguration {
                keycodes: Vec::new(),
                touchscreen: Some((width.max(1) as u16, height.max(1) as u16)),
            },
            video: VideoConfiguration {
                resolution: Wifi::video_resolution::Enum::_720p,
                fps: Wifi::video_fps::Enum::_30,
                dpi: 160,
            },
            sensors: SensorInformation { sensors: HashSet::new() },
        };
        std::thread::Builder::new().name("OLA-AndroidAuto".into()).spawn(move || {
            let runtime = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
                Ok(runtime) => runtime,
                Err(error) => { host.status(&format!("Tokio runtime failed: {error}")); return; }
            };
            runtime.block_on(async move {
                let stream = match tokio::net::TcpStream::from_std(std_stream) {
                    Ok(stream) => stream,
                    Err(error) => { host.status(&format!("TCP socket setup failed: {error}")); return; }
                };
                host.status("Starting Android Auto TLS and projection session…");
                tokio::select! {
                    result = crate::run_tcp_stream(stream, configuration(), Box::new(host)) => {
                        if let Err(error) = result {
                            log::error!("Android Auto session failed: {error:?}");
                        }
                    }
                    _ = stop_rx => {}
                }
            });
        }).map_err(|e| e.to_string())?;
        Ok(id)
    })();
    match result {
        Ok(id) => id as jlong,
        Err(error) => {
            if let Ok(mut env) = env.get_java_vm().and_then(|vm| vm.attach_current_thread()) {
                if let Ok(message) = env.new_string(error) {
                    let object = JObject::from(message);
                    let _ = env.call_method(activity, "onNativeStatus", "(Ljava/lang/String;)V", &[JValue::Object(&object)]);
                }
            }
            0
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_androidauto_headunit_NativeProjection_nativeTouch(
    _env: JNIEnv, _class: JClass, handle: jlong, x: jint, y: jint, action: jint, pointer: jint,
) {
    let sender = handles().lock().ok().and_then(|map| map.get(&(handle as i64)).map(|h| h.input.clone()));
    let Some(sender) = sender else { return; };
    let touch_action = match action {
        0 => Wifi::TouchAction::Enum::PRESS,
        1 => Wifi::TouchAction::Enum::RELEASE,
        2 => Wifi::TouchAction::Enum::DRAG,
        5 => Wifi::TouchAction::Enum::POINTER_DOWN,
        6 => Wifi::TouchAction::Enum::POINTER_UP,
        _ => return,
    };
    let mut location = Wifi::TouchLocation::new();
    location.set_x(x.max(0) as u32);
    location.set_y(y.max(0) as u32);
    location.set_pointer_id(pointer.max(0) as u32);
    let mut touch = Wifi::TouchEvent::new();
    touch.touch_location.push(location);
    touch.set_action_index(0);
    touch.set_touch_action(touch_action);
    let mut event = Wifi::InputEventIndication::new();
    event.set_timestamp(SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_micros() as u64);
    event.set_touch_event(touch);
    let _ = sender.try_send(AndroidAutoMessage::Input(event).sendable());
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_org_androidauto_headunit_NativeProjection_nativeStop(
    _env: JNIEnv, _class: JClass, handle: jlong,
) {
    if let Ok(mut map) = handles().lock() {
        if let Some(mut native) = map.remove(&(handle as i64)) {
            if let Some(stop) = native.stop.take() { let _ = stop.send(()); }
        }
    }
}
