// Runs each job through a worker and collects the results.
export class JobQueue {
  constructor(worker) {
    this.worker = worker;
    this.results = [];
  }

  processAll(jobs) {
    jobs.forEach(async (job) => {
      this.results.push(await this.worker(job));
    });
    return this.results;
  }
}
