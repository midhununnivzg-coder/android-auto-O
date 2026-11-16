//! Contains sensor channel code

use super::{
    AndroidAutoCommonMessage, AndroidAutoConfiguration, AndroidAutoFrame, ChannelDescriptor,
    ChannelHandlerTrait, ChannelId, FrameHeader, FrameHeaderContents, FrameHeaderType,
};
use crate::{AndroidAutoMainTrait, StreamMux, Wifi};
use protobuf::Message;

/// A message about sensors in android auto
#[derive(Debug)]
pub enum SensorMessage {
    /// A request to start a specific sensor
    SensorStartRequest(ChannelId, Wifi::SensorStartRequestMessage),
    /// A response to the sensor start request
    SensorStartResponse(ChannelId, Wifi::SensorStartResponseMessage),
    /// A message containing sensor data
    Event(ChannelId, Wifi::SensorEventIndication),
}

impl From<SensorMessage> for AndroidAutoFrame {
    fn from(value: SensorMessage) -> Self {
        match value {
            SensorMessage::SensorStartRequest(chan, m) => {
                let mut data = m.write_to_bytes().unwrap_or_default();
                let t = (Wifi::sensor_channel_message::Enum::SENSOR_START_REQUEST as u16).to_be_bytes();
                let mut payload = vec![t[0], t[1]];
                payload.append(&mut data);
                AndroidAutoFrame {
                    header: FrameHeader {
                        channel_id: chan,
                        frame: FrameHeaderContents::new(true, FrameHeaderType::Single, false),
                    },
                    data: payload,
                }
            },
            SensorMessage::SensorStartResponse(chan, m) => {
                let mut data = m.write_to_bytes().unwrap();
                let t = Wifi::sensor_channel_message::Enum::SENSOR_START_RESPONSE as u16;
                let t = t.to_be_bytes();
                let mut m = Vec::new();
                m.push(t[0]);
                m.push(t[1]);
                m.append(&mut data);
                AndroidAutoFrame {
                    header: FrameHeader {
                        channel_id: chan,
                        frame: FrameHeaderContents::new(true, FrameHeaderType::Single, false),
                    },
                    data: m,
                }
            }
            SensorMessage::Event(chan, m) => {
                let mut data = m.write_to_bytes().unwrap();
                let t = Wifi::sensor_channel_message::Enum::SENSOR_EVENT_INDICATION as u16;
                let t = t.to_be_bytes();
                let mut m = Vec::new();
                m.push(t[0]);
                m.push(t[1]);
                m.append(&mut data);
                AndroidAutoFrame {
                    header: FrameHeader {
                        channel_id: chan,
                        frame: FrameHeaderContents::new(true, FrameHeaderType::Single, false),
                    },
                    data: m,
                }
            }
        }
    }
}

impl TryFrom<&AndroidAutoFrame> for SensorMessage {
    type Error = String;
    fn try_from(value: &AndroidAutoFrame) -> Result<Self, Self::Error> {
        use protobuf::Enum;
        if value.data.len() < 2 {
            return Err("Sensor-channel frame is shorter than its 2-byte message type".to_string());
        }
        let mut ty = [0u8; 2];
        ty.copy_from_slice(&value.data[0..2]);
        let ty = u16::from_be_bytes(ty);
        if let Some(sys) = Wifi::sensor_channel_message::Enum::from_i32(ty as i32) {
            match sys {
                Wifi::sensor_channel_message::Enum::SENSOR_START_REQUEST => {
                    let m = Wifi::SensorStartRequestMessage::parse_from_bytes(&value.data[2..]);
                    match m {
                        Ok(m) => Ok(Self::SensorStartRequest(value.header.channel_id, m)),
                        Err(e) => Err(e.to_string()),
                    }
                }
                Wifi::sensor_channel_message::Enum::SENSOR_START_RESPONSE => {
                    let m = Wifi::SensorStartResponseMessage::parse_from_bytes(&value.data[2..])
                        .map_err(|e| format!("Invalid sensor-start response: {e}"))?;
                    Ok(Self::SensorStartResponse(value.header.channel_id, m))
                },
                Wifi::sensor_channel_message::Enum::SENSOR_EVENT_INDICATION => {
                    let m = Wifi::SensorEventIndication::parse_from_bytes(&value.data[2..])
                        .map_err(|e| format!("Invalid sensor event: {e}"))?;
                    Ok(Self::Event(value.header.channel_id, m))
                },
                Wifi::sensor_channel_message::Enum::NONE => Err("Received empty sensor-channel message".to_string()),
            }
        } else {
            Err(format!("Not converted message: {:x?}", value.data))
        }
    }
}

/// The handler for the sensor channel in the android auto protocol.
pub struct SensorChannelHandler {}

impl ChannelHandlerTrait for SensorChannelHandler {
    fn build_channel<T: AndroidAutoMainTrait + ?Sized>(
        &self,
        _config: &AndroidAutoConfiguration,
        chanid: ChannelId,
        main: &T,
    ) -> Option<Wifi::ChannelDescriptor> {
        let mut chan = ChannelDescriptor::new();
        let mut sensor = Wifi::SensorChannel::new();
        let s = main.get_supported_sensors();
        for s in &s.sensors {
            sensor.sensors.push({
                let mut sensor1 = Wifi::Sensor::new();
                sensor1.set_type(*s);
                sensor1
            });
        }
        chan.sensor_channel.0.replace(Box::new(sensor));
        chan.set_channel_id(chanid as u32);
        if !chan.is_initialized() {
            panic!("Channel not initialized?");
        }
        Some(chan)
    }

    async fn receive_data<T: super::AndroidAutoMainTrait + ?Sized>(
        &self,
        msg: AndroidAutoFrame,
        stream: &crate::WriteHalf,
        _config: &AndroidAutoConfiguration,
        main: &T,
    ) -> Result<(), super::FrameIoError> {
        let channel = msg.header.channel_id;
        let msg2: Result<SensorMessage, String> = (&msg).try_into();
        if let Ok(msg2) = msg2 {
            match msg2 {
                SensorMessage::Event(_chan, m) => log::debug!("Sensor event from phone: {:?}", m),
                SensorMessage::SensorStartResponse(_, response) => log::debug!("Sensor start response: {:?}", response),
                SensorMessage::SensorStartRequest(_chan, m) => {
                    let mut m2 = Wifi::SensorStartResponseMessage::new();

                    let stat = match main.start_sensor(m.sensor_type()).await {
                        Ok(_) => Wifi::status::Enum::OK,
                        Err(_) => Wifi::status::Enum::FAIL,
                    };
                    m2.set_status(stat);
                    stream
                        .write_frame(SensorMessage::SensorStartResponse(channel, m2).into())
                        .await?;
                }
            }
            return Ok(());
        }
        let msg2: Result<AndroidAutoCommonMessage, String> = (&msg).try_into();
        if let Ok(msg2) = msg2 {
            match msg2 {
                AndroidAutoCommonMessage::ChannelOpenResponse(_, response) => {
                    log::debug!("Channel-open response received: {:?}", response);
                },
                AndroidAutoCommonMessage::ChannelOpenRequest(_m) => {
                    let mut m2 = Wifi::ChannelOpenResponse::new();
                    m2.set_status(Wifi::status::Enum::OK);
                    stream
                        .write_frame(
                            AndroidAutoCommonMessage::ChannelOpenResponse(channel, m2).into(),
                        )
                        .await?;
                }
            }
            return Ok(());
        }
        Err(super::FrameIoError::SslSession(format!(
            "Unrecognized sensor-channel message on channel {}: {:x?}",
            channel, msg.data
        )))
    }
}
