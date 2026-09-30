use super::{Reader, DAMAGED};
use flate2::{Decompress, FlushDecompress, Status};

pub(super) enum Rows<'a> {
    Raw(Reader<'a>),
    Rle {
        data: Reader<'a>,
        lengths: Reader<'a>,
    },
    Zip {
        data: &'a [u8],
        decoder: Box<Decompress>,
        finished: bool,
        predict: bool,
    },
}

impl<'a> Rows<'a> {
    pub fn new(bytes: &'a [u8], height: usize) -> Result<Self, String> {
        let mut data = Reader(bytes);
        Ok(match data.u16()? {
            0 => Self::Raw(data),
            1 => {
                let lengths = Reader(data.take(height * 2)?);
                Self::Rle { data, lengths }
            }
            compression @ 2..=3 => Self::Zip {
                data: data.0,
                decoder: Box::new(Decompress::new(true)),
                finished: false,
                predict: compression == 3,
            },
            _ => return Err("PSD 压缩方式不受支持".into()),
        })
    }

    pub fn read(&mut self, row: &mut [u8]) -> Result<(), String> {
        match self {
            Self::Raw(data) => row.copy_from_slice(data.take(row.len())?),
            Self::Rle { data, lengths } => {
                let length = lengths.u16()? as usize;
                let mut encoded = Reader(data.take(length)?);
                let mut output = &mut row[..];
                while !encoded.0.is_empty() {
                    let control = encoded.u8()? as i8;
                    if control == -128 {
                        continue;
                    }
                    let length = if control >= 0 {
                        control as usize + 1
                    } else {
                        (1 - i16::from(control)) as usize
                    };
                    let (target, rest) = output.split_at_mut_checked(length).ok_or(DAMAGED)?;
                    if control >= 0 {
                        target.copy_from_slice(encoded.take(length)?);
                    } else {
                        target.fill(encoded.u8()?);
                    }
                    output = rest;
                }
                if !output.is_empty() {
                    return Err(DAMAGED.into());
                }
            }
            Self::Zip {
                data,
                decoder,
                finished,
                predict,
            } => {
                let mut written = 0;
                while written < row.len() {
                    if *finished {
                        return Err(DAMAGED.into());
                    }
                    written += inflate(data, decoder, finished, &mut row[written..])?;
                }
                if *predict {
                    for x in 1..row.len() {
                        row[x] = row[x].wrapping_add(row[x - 1]);
                    }
                }
            }
        }
        Ok(())
    }

    pub fn finish(self) -> Result<(), String> {
        match self {
            Self::Raw(data) => data.padding(),
            Self::Rle { data, lengths } => {
                if !lengths.0.is_empty() {
                    return Err(DAMAGED.into());
                }
                data.padding()
            }
            Self::Zip {
                data,
                mut decoder,
                mut finished,
                ..
            } => {
                while !finished {
                    if inflate(data, &mut decoder, &mut finished, &mut [0])? != 0 {
                        return Err(DAMAGED.into());
                    }
                }
                Reader(&data[decoder.total_in() as usize..]).padding()
            }
        }
    }
}

fn inflate(
    data: &[u8],
    decoder: &mut Decompress,
    finished: &mut bool,
    output: &mut [u8],
) -> Result<usize, String> {
    let (input, written) = (decoder.total_in(), decoder.total_out());
    let status = decoder
        .decompress(&data[input as usize..], output, FlushDecompress::None)
        .map_err(|_| DAMAGED)?;
    *finished = status == Status::StreamEnd;
    if !*finished && decoder.total_in() == input && decoder.total_out() == written {
        return Err(DAMAGED.into());
    }
    Ok((decoder.total_out() - written) as usize)
}
