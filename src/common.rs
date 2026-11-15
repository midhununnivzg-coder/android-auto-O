//! Messages common to all channels

use super::{AndroidAutoFrame, ChannelId, FrameHeader, FrameHeaderContents, FrameHeaderType};
use crate::Wifi;
use protobuf::{Enum, Message};

/// Messages common to all android auto channels.
#[derive(Debug)]
pub enum AndroidAutoCommonMessage {
    /// A request to the open the channel from the compatible android auto device
    ChannelOpenRequest(Wifi::ChannelOpenRequest),
    /// A response to a channel open request
    ChannelOpenResponse(ChannelId, Wifi::ChannelOpenResponse),
}

impl TryFrom<&AndroidAutoFrame> for AndroidAutoCommonMessage {
    type Error = String;
    fn try_from(value: &AndroidAutoFrame) -> Result<Self, Self::Error> {
        if value.data.len() < 2 {
            return Err("Common channel frame is shorter than its 2-byte message type".to_string());
        }
        let mut ty = [0u8; 2];
        ty.copy_from_slice(&value.data[0..2]);
        let ty = u16::from_be_bytes(ty);
        if value.header.frame.get_control() {
            let w = Wifi::CommonMessage::from_i32(ty as i32);
            if let Some(m) = w {
                match m {
                    Wifi::CommonMessage::CHANNEL_OPEN_RESPONSE => {
                        let m = Wifi::ChannelOpenResponse::parse_from_bytes(&value.data[2..])
                            .map_err(|e| format!("Invalid channel-open response: {e}"))?;
                        Ok(AndroidAutoCommonMessage::ChannelOpenResponse(value.header.channel_id, m))
                    }
                    Wifi::CommonMessage::CHANNEL_OPEN_REQUEST => {
                        let m = Wifi::ChannelOpenRequest::parse_from_bytes(&value.data[2..]);
                        match m {
                            Ok(m) => Ok(AndroidAutoCommonMessage::ChannelOpenRequest(m)),
                            Err(e) => Err(format!("Invalid channel open request: {}", e)),
                        }
                    }
                }
            } else {
                Err(format!("Unknown packet type 0x{:x}", ty))
            }
        } else {
            Err(format!(
                "Unhandled specific message for channel {:?} {:x?}",
                value.header.channel_id, value.data
            ))
        }
    }
}

impl From<AndroidAutoCommonMessage> for AndroidAutoFrame {
    fn from(value: AndroidAutoCommonMessage) -> Self {
        match value {
            AndroidAutoCommonMessage::ChannelOpenResponse(chan, m) => {
                let mut data = m.write_to_bytes().unwrap();
                let t = Wifi::CommonMessage::CHANNEL_OPEN_RESPONSE as u16;
                let t = t.to_be_bytes();
                let mut m = Vec::new();
                m.push(t[0]);
                m.push(t[1]);
                m.append(&mut data);
                AndroidAutoFrame {
                    header: FrameHeader {
                        channel_id: chan,
                        frame: FrameHeaderContents::new(true, FrameHeaderType::Single, true),
                    },
                    data: m,
                }
            }
            AndroidAutoCommonMessage::ChannelOpenRequest(m) => {
                let mut data = m.write_to_bytes().unwrap_or_default();
                let t = (Wifi::CommonMessage::CHANNEL_OPEN_REQUEST as u16).to_be_bytes();
                let mut frame_data = vec![t[0], t[1]];
                frame_data.append(&mut data);
                AndroidAutoFrame {
                    header: FrameHeader {
                        channel_id: 0,
                        frame: FrameHeaderContents::new(true, FrameHeaderType::Single, true),
                    },
                    data: frame_data,
                }
            }
        }
    }
}
