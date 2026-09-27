use crate::model::*;
use std::collections::{HashSet, VecDeque};
use std::sync::Arc;

#[derive(Default)]
pub struct History {
    pub undo: VecDeque<Document>,
    pub redo: Vec<Document>,
}

impl History {
    pub fn push(&mut self, before: Document, current: &Document) {
        self.redo.clear();
        self.undo.push_back(before);
        while self.undo.len() > MAX_HISTORY_ENTRIES
            || (!self.undo.is_empty() && self.retained_bytes(current) > MAX_HISTORY_BYTES)
        {
            self.undo.pop_front();
        }
    }

    fn retained_bytes(&self, current: &Document) -> usize {
        let live: HashSet<_> = current
            .layers
            .iter()
            .flat_map(|layer| layer.tiles.values())
            .map(Arc::as_ptr)
            .collect();
        let retained: HashSet<_> = self
            .undo
            .iter()
            .chain(self.redo.iter())
            .flat_map(|doc| &doc.layers)
            .flat_map(|layer| layer.tiles.values())
            .map(Arc::as_ptr)
            .filter(|ptr| !live.contains(ptr))
            .collect();
        retained.len() * TILE_BYTES
    }
}
