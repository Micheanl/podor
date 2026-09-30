use crate::model::*;
use std::collections::{HashSet, VecDeque};

#[derive(Default)]
pub struct History {
    pub undo: VecDeque<Snapshot>,
    pub redo: Vec<Snapshot>,
}

pub struct Snapshot {
    pub document: Document,
    pub content_id: u64,
    pub pixels_changed: bool,
}

impl History {
    pub fn push(
        &mut self,
        before: Document,
        content_id: u64,
        current: &Document,
        pixels_changed: bool,
    ) {
        self.redo.clear();
        self.undo.push_back(Snapshot {
            document: before,
            content_id,
            pixels_changed,
        });
        while self.undo.len() > MAX_HISTORY_ENTRIES
            || (!self.undo.is_empty() && self.retained_bytes(current) > MAX_HISTORY_BYTES)
        {
            self.undo.pop_front();
        }
    }

    fn retained_bytes(&self, current: &Document) -> usize {
        let live: HashSet<_> = current.resources().map(|(ptr, _)| ptr).collect();
        let retained: HashSet<_> = self
            .undo
            .iter()
            .chain(self.redo.iter())
            .flat_map(|snapshot| snapshot.document.resources())
            .filter(|(ptr, _)| !live.contains(ptr))
            .collect();
        retained.into_iter().map(|(_, bytes)| bytes).sum()
    }
}
