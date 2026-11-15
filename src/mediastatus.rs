//! This is for the media status channel handler code

use protobuf::Message;

use crate::{
    AndroidAutoConfiguration, AndroidAutoFrame, AndroidAutoMainTrait, ChannelHandlerTrait,
    ChannelId, StreamMux, Wifi, common::AndroidAutoCommonMessage,
};

/// A message about the media status of currently playing media
#[derive(Debug)]
enum MediaStatusMessage {
    /// A message containing basic information about changes to the currently playing media
    Playback(ChannelId, Wifi::MediaInfoChannelPlaybackData),
    /// The metadata containing information about the media currently playing
    Metadata(ChannelId, Wifi::MediaInfoChannelMetadataData),
    /// The media status message was invalid for some reason
    Invalid,
}

impl From<MediaStatusMessage> for AndroidAutoFrame {
    fn from(value: MediaStatusMessage) -> Self {
        let (channel, message_type, mut payload) = match value {
            MediaStatusMessage::Playback(channel, message) => (
                channel,
                Wifi::media_info_channel_message::Enum::PLAYBACK,
                message.write_to_bytes().unwrap_or_default(),
            ),
            MediaStatusMessage::Metadata(channel, message) => (
                channel,
                Wifi::media_info_channel_message::Enum::METADATA,
                message.write_to_bytes().unwrap_or_default(),
            ),
            MediaStatusMessage::Invalid => (
                0,
                Wifi::media_info_channel_message::Enum::NONE,
                Vec::new(),
            ),
        };
        let mut data = (message_type as u16).to_be_bytes().to_vec();
        data.append(&mut payload);
        AndroidAutoFrame {
            header: super::FrameHeader {
                channel_id: channel,
                frame: super::FrameHeaderContents::new(true, super::FrameHeaderType::Single, false),
            },
            data,
        }
    }
}

impl TryFrom<&AndroidAutoFrame> for MediaStatusMessage {
    type Error = String;
    fn try_from(value: &AndroidAutoFrame) -> Result<Self, Self::Error> {
        use protobuf::Enum;
        if value.data.len() < 2 {
            return Err("Media-status frame is shorter than its 2-byte message type".to_string());
        }
        let mut ty = [0u8; 2];
        ty.copy_from_slice(&value.data[0..2]);
        let ty = u16::from_be_bytes(ty);
        if let Some(sys) = Wifi::media_info_channel_message::Enum::from_i32(ty as i32) {
            match sys {
                Wifi::media_info_channel_message::Enum::PLAYBACK => {
                    let m = Wifi::MediaInfoChannelPlaybackData::parse_from_bytes(&value.data[2..]);
                    match m {
                        Ok(m) => Ok(Self::Playback(value.header.channel_id, m)),
                        Err(_) => Ok(Self::Invalid),
                    }
                }
                Wifi::media_info_channel_message::Enum::METADATA => {
                    let m = Wifi::MediaInfoChannelMetadataData::parse_from_bytes(&value.data[2..]);
                    match m {
                        Ok(m) => Ok(Self::Metadata(value.header.channel_id, m)),
                        Err(_) => Ok(Self::Invalid),
                    }
                }
                Wifi::media_info_channel_message::Enum::NONE => Err("Received empty media-status message".to_string()),
            }
        } else {
            Err(format!("Not converted message: {:x?}", value.data))
        }
    }
}

/// The handler for media status for the android auto protocol
pub struct MediaStatusChannelHandler {}

impl ChannelHandlerTrait for MediaStatusChannelHandler {
    fn build_channel<T: AndroidAutoMainTrait + ?Sized>(
        &self,
        _config: &AndroidAutoConfiguration,
        chanid: ChannelId,
        _main: &T,
    ) -> Option<Wifi::ChannelDescriptor> {
        let mut chan = Wifi::ChannelDescriptor::new();
        chan.set_channel_id(chanid as u32);
        let mchan = Wifi::MediaInfoChannel::new();
        chan.media_infoChannel.0.replace(Box::new(mchan));
        if !chan.is_initialized() {
            panic!("Channel not initialized?");
        }
        Some(chan)
    }

    async fn receive_data<T: AndroidAutoMainTrait + ?Sized>(
        &self,
        msg: AndroidAutoFrame,
        stream: &crate::WriteHalf,
        _config: &AndroidAutoConfiguration,
        _main: &T,
    ) -> Result<(), super::FrameIoError> {
        let channel = msg.header.channel_id;
        let msg2: Result<MediaStatusMessage, String> = (&msg).try_into();
        if let Ok(msg2) = msg2 {
            match msg2 {
                MediaStatusMessage::Metadata(_, m) => {
                    log::info!("Metadata {:?}", m);
                }
                MediaStatusMessage::Playback(_, m) => {
                    log::info!("Playback {:?}", m);
                }
                MediaStatusMessage::Invalid => {
                    log::error!("Received invalid media info frame");
                }
            }
            return Ok(());
        }
        let msg3: Result<AndroidAutoCommonMessage, String> = (&msg).try_into();
        if let Ok(msg2) = msg3 {
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
            "Unrecognized media-status channel message on channel {}: {:x?}",
            channel, msg.data
        )))
    }
}
